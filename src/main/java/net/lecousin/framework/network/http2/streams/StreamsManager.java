package net.lecousin.framework.network.http2.streams;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

import net.lecousin.framework.collections.map.IntegerMap;
import net.lecousin.framework.collections.map.IntegerMapRBT;
import net.lecousin.framework.collections.sort.RedBlackTreeInteger;
import net.lecousin.framework.concurrent.Executable;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.TCPRemote;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.frame.HTTP2Data;
import net.lecousin.framework.network.http2.frame.HTTP2Frame;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.frame.HTTP2GoAway;
import net.lecousin.framework.network.http2.frame.HTTP2Settings;
import net.lecousin.framework.network.http2.frame.HTTP2WindowUpdate;
import net.lecousin.framework.network.http2.hpack.HPackCompress;
import net.lecousin.framework.network.http2.hpack.HPackDecompress;
import net.lecousin.framework.util.Pair;

public abstract class StreamsManager {
	
	protected TCPRemote remote;
	private boolean clientMode;
	private HTTP2Settings localSettings;
	private HTTP2Settings remoteSettings;
	private Async<IOException> connectionReady = new Async<>();
	private int sendTimeout;
	private Logger logger;
	private ByteArrayCache bufferCache;
	private ConnectionStreamHandler connectionStream = new ConnectionStreamHandler();
	private IntegerMapRBT<StreamHandler> dataStreams = new IntegerMapRBT<StreamHandler>(10) {
		@Override
		protected int hash(int key) {
			return (key >> 1) % 10;
		}
	};
	private int lastRemoteStreamId = 0;
	private int lastLocalStreamId = -1;
	private boolean closing = false;

	HPackDecompress decompressionContext;
	HPackCompress compressionContext;
	int currentDecompressionStreamId = -1;
	private int currentCompressionStreamId = -1;
	private LinkedList<Pair<Integer, AsyncSupplier<Pair<HPackCompress, Integer>, NoException>>> compressionContextRequests = new LinkedList<>();
	
	private FrameState currentFrameState = FrameState.START;
	private HTTP2FrameHeader currentFrameHeader = null;
	private HTTP2FrameHeader.Consumer currentFrameHeaderConsumer = null;
	private StreamHandler currentStream = null;
	
	public StreamsManager(
		TCPRemote remote, boolean clientMode,
		HTTP2Settings localSettings, HTTP2Settings initialRemoteSettings,
		int sendTimeout,
		Logger logger, ByteArrayCache bufferCache
	) {
		this.remote = remote;
		this.clientMode = clientMode;
		this.logger = logger;
		this.bufferCache = bufferCache;
		this.sendTimeout = sendTimeout;
		this.localSettings = localSettings;
		remoteSettings = initialRemoteSettings;
		compressionContext = new HPackCompress((int)localSettings.getMaxHeaderListSize());
		if (remoteSettings != null) {
			decompressionContext = new HPackDecompress((int)remoteSettings.getHeaderTableSize());
			dependencyTree.sendWindowSize = remoteSettings.getWindowSize();
		}
		dependencyTree.recvWindowSize = localSettings.getWindowSize();
		dependencyNodes.put(0, dependencyTree);
		
		// send our settings
		sendFrame(new HTTP2Settings.Writer(localSettings, true), false);
		
		if (remoteSettings != null) {
			// send ACK
			sendFrame(new HTTP2Frame.EmptyWriter(
				new HTTP2FrameHeader(0, HTTP2FrameHeader.TYPE_SETTINGS, HTTP2Settings.FLAG_ACK, 0)), false);
			connectionReady.unblock();
		}

		// TODO on remote closed, clean everything
	}
	
	public Async<IOException> getConnectionReady() {
		return connectionReady;
	}
	
	public abstract DataHandler createDataHandler(int streamId);
	
	public boolean isClosing() {
		return closing;
	}
	
	HTTP2FrameHeader getCurrentFrameHeader() {
		return currentFrameHeader;
	}
	
	public Logger getLogger() {
		return logger;
	}
	
	private enum FrameState {
		START, HEADER, PAYLOAD
	}

	/** Consume data, and return an asynchronous point to signal when new data is expected. */
	public Async<IOException> consumeDataFromRemote(ByteBuffer data) {
		Async<IOException> onConsumed = new Async<>();
		consumeDataFromRemote(data, onConsumed);
		return onConsumed;
	}
	
	private void consumeDataFromRemote(ByteBuffer data, Async<IOException> onConsumed) {
		switch (currentFrameState) {
		case START:
			startConsumeFrameHeader(data, onConsumed);
			break;
		case HEADER:
			continueConsumeFrameHeader(data, onConsumed);
			break;
		case PAYLOAD:
			continueConsumeFramePayload(data, onConsumed);
			break;
		default: // not possible
		}
	}
	
	private void startConsumeFrameHeader(ByteBuffer data, Async<IOException> onConsumed) {
		if (logger.trace())
			logger.trace("Start receiving new frame from " + remote);
		currentFrameHeader = new HTTP2FrameHeader();
		currentFrameHeaderConsumer = currentFrameHeader.createConsumer();
		boolean headerReady = currentFrameHeaderConsumer.consume(data);
		if (!headerReady) {
			currentFrameState = FrameState.HEADER;
			onConsumed.unblock();
			return;
		}
		startConsumeFramePayload(data, onConsumed);
	}
	
	
	private void continueConsumeFrameHeader(ByteBuffer data, Async<IOException> onConsumed) {
		boolean headerReady = currentFrameHeaderConsumer.consume(data);
		if (!headerReady) {
			onConsumed.unblock();
			return;
		}
		startConsumeFramePayload(data, onConsumed);
	}

	private void startConsumeFramePayload(ByteBuffer data, Async<IOException> onConsumed) {
		currentFrameHeaderConsumer = null;
		currentFrameState = FrameState.PAYLOAD;
		if (logger.trace())
			logger.trace("Frame header received with type " + currentFrameHeader.getType() + ", flags " + currentFrameHeader.getFlags()
				+ ", stream " + currentFrameHeader.getStreamId() + ", payload length " + currentFrameHeader.getPayloadLength());
		currentStream = processFrameHeader();
		if (currentStream == null)
			return; // connection error
		if (currentFrameHeader.getPayloadLength() == 0) {
			endOfFrame(data, onConsumed);
			return;
		}
		if (!data.hasRemaining()) {
			onConsumed.unblock();
			return;
		}
		currentStream.consumeFramePayload(this, data, onConsumed);
	}	
	
	private void continueConsumeFramePayload(ByteBuffer data, Async<IOException> onConsumed) {
		currentStream.consumeFramePayload(this, data, onConsumed);
	}
	
	void endOfFrame(ByteBuffer data, Async<IOException> onConsumed) {
		currentFrameState = FrameState.START;
		currentFrameHeader = null;
		currentStream = null;
		if (!data.hasRemaining()) {
			onConsumed.unblock();
			return;
		}
		// TODO check number of processed requests and delay receiving the next one if needed
		// TODO launch it in a new task to avoid recursivity
		consumeDataFromRemote(data, onConsumed);
	}

	void applyRemoteSettings(HTTP2Settings settings) {
		if (remoteSettings == null) {
			remoteSettings = settings;
			decompressionContext = new HPackDecompress((int)settings.getHeaderTableSize());
			connectionReady.unblock();
		} else {
			long previousWindowSize = remoteSettings.getWindowSize();
			remoteSettings.set(settings);
			decompressionContext.updateMaximumDynamicTableSize((int)settings.getHeaderTableSize());
			if (remoteSettings.getWindowSize() != previousWindowSize) {
				long diff = remoteSettings.getWindowSize() - previousWindowSize;
				synchronized (dependencyTree) {
					dependencyTree.adjustSendWindowSize(diff);
				}
			}
		}
	}
	
	void connectionError(int errorCode, String debugMessage) {
		if (logger.debug())
			logger.debug("Send connection error " + errorCode + ": " + debugMessage);
		HTTP2GoAway.Writer frame = new HTTP2GoAway.Writer(lastRemoteStreamId, errorCode,
			debugMessage != null ? debugMessage.getBytes(StandardCharsets.UTF_8) : null);
		sendFrame(frame, true);
	}
	
	/** Start processing a frame, return the stream handler or null to stop everything. */
	private StreamHandler processFrameHeader() {
		if (closing)
			return null;
		if (logger.debug())
			logger.debug("Received frame: " + currentFrameHeader);
		StreamHandler stream;
		if (currentFrameHeader.getStreamId() == 0) {
			stream = connectionStream;
		} else {
			// client stream identifiers MUST be odd, server stream even
			stream = dataStreams.get(currentFrameHeader.getStreamId());
			if (stream == null) {
				// it may be a request to open a new stream, or remaining frames on a previous stream
				if ((currentFrameHeader.getStreamId() % 2) == (clientMode ? 1 : 0) ||
					(currentFrameHeader.getStreamId() <= lastRemoteStreamId)) {
					// it may be remaining frames sent before we sent a reset stream to the remote
					// we need to process it as it may change connection contexts (i.e. decompression context)
					switch (currentFrameHeader.getType()) {
					case HTTP2FrameHeader.TYPE_HEADERS:
					case HTTP2FrameHeader.TYPE_CONTINUATION:
						if (logger.debug())
							logger.debug("Received headers frame on closed stream, process it: " + currentFrameHeader);
						stream = new SkipHeadersFrame(currentFrameHeader.getStreamId());
						break;
					case HTTP2FrameHeader.TYPE_PRIORITY:
						// TODO
					case HTTP2FrameHeader.TYPE_DATA:
					case HTTP2FrameHeader.TYPE_RST_STREAM:
						if (logger.debug())
							logger.debug("Received frame on closed stream, skip it: " + currentFrameHeader);
						stream = new SkipFrame();
						break;
					case HTTP2FrameHeader.TYPE_WINDOW_UPDATE:
						DependencyNode node;
						synchronized (dependencyTree) {
							node = dependencyNodes.get(currentFrameHeader.getStreamId());
						}
						if (node == null) {
							if (logger.debug())
								logger.debug("Received window update on closed stream, skip it: "
									+ currentFrameHeader);
							stream = new SkipFrame();
						} else {
							if (logger.debug())
								logger.debug("Received window update on closed stream with pending data, process it: "
									+ currentFrameHeader);
							stream = new ProcessWindowUpdateFrame();
						}
						break;
					default:
						// The identifier of a newly established stream MUST be numerically
						// greater than all streams that the initiating endpoint has opened or reserved.
						connectionError(HTTP2Error.Codes.PROTOCOL_ERROR, "Invalid stream id");
						return null;
					}
				} else {
					// this is a new stream from the remote
					// TODO check if maximum is reached
					stream = openRemoteStream(currentFrameHeader.getStreamId());
				}
			}
		}
		if (!stream.startFrame(this, currentFrameHeader))
			return null;
		return stream;
	}
	
	private DataStreamHandler openRemoteStream(int id) {
		lastRemoteStreamId = id;
		DataStreamHandler stream = new DataStreamHandler(id);
		dataStreams.put(id, stream);
		synchronized (dependencyTree) {
			addStreamNode(dependencyTree, id, 16);
		}
		return stream;
	}
	
	private int openLocalStream() {
		lastLocalStreamId += 2;
		DataStreamHandler stream = new DataStreamHandler(lastLocalStreamId);
		dataStreams.put(lastLocalStreamId, stream);
		synchronized (dependencyTree) {
			addStreamNode(dependencyTree, lastLocalStreamId, 16);
		}
		return lastLocalStreamId;
	}
	
	public AsyncSupplier<HPackCompress, NoException> reserveCompressionContext(int streamId) {
		synchronized (compressionContextRequests) {
			if (currentCompressionStreamId == -1) {
				currentCompressionStreamId = streamId;
				return new AsyncSupplier<>(compressionContext, null);
			}
			AsyncSupplier<HPackCompress, NoException> result = new AsyncSupplier<>();
			AsyncSupplier<Pair<HPackCompress, Integer>, NoException> request = new AsyncSupplier<>();
			compressionContextRequests.add(new Pair<>(Integer.valueOf(streamId), request));
			request.onDone(p -> result.unblockSuccess(p.getValue1()), result);
			return result;
		}
	}

	public AsyncSupplier<Pair<HPackCompress, Integer>, NoException> reserveCompressionContextAndOpenStream() {
		synchronized (compressionContextRequests) {
			if (currentCompressionStreamId == -1) {
				currentCompressionStreamId = openLocalStream();
				return new AsyncSupplier<>(new Pair<>(compressionContext, Integer.valueOf(currentCompressionStreamId)), null);
			}
			AsyncSupplier<Pair<HPackCompress, Integer>, NoException> request = new AsyncSupplier<>();
			compressionContextRequests.add(new Pair<>(null, request));
			return request;
		}
	}
	
	public void releaseCompressionContext(int streamId) {
		synchronized (compressionContextRequests) {
			if (currentCompressionStreamId != streamId)
				return;
			Pair<Integer, AsyncSupplier<Pair<HPackCompress, Integer>, NoException>> request = compressionContextRequests.poll();
			if (request == null) {
				currentCompressionStreamId = -1;
				return;
			}
			if (request.getValue1() == null) {
				currentCompressionStreamId = openLocalStream();
			} else {
				currentCompressionStreamId = request.getValue1().intValue();
			}
			request.getValue2().unblockSuccess(new Pair<>(compressionContext, Integer.valueOf(currentCompressionStreamId)));
		}
	}
	
	void closeStream(DataStreamHandler stream) {
		dataStreams.remove(stream.getStreamId());
	}
	
	public void endOfStream(int streamId) {
		synchronized (dependencyTree) {
			DependencyNode node = dependencyNodes.get(streamId);
			if (node != null)
				node.endOfStream();
		}
	}
	
	private Async<IOException> lastSend = new Async<>(true);
	private static final int MAX_FRAMES_PRODUCED = 5;
	
	public void sendFrame(HTTP2Frame.Writer frame, boolean closeAfter) {
		if (logger.debug()) logger.debug("Frame to send on stream " + frame.getStreamId()
			+ ": " + HTTP2FrameHeader.getTypeName(frame.getType()));
		synchronized (this) {
			if (closing)
				return;
			
			// if last frame, this is a connection error, we can send it as soon as possible
			if (closeAfter) {
				closing = true;
				LinkedList<ByteBuffer> list = new LinkedList<>();
				while (frame.canProduceMore()) {
					ByteArray data = frame.produce((int)remoteSettings.getMaxFrameSize(), bufferCache);
					list.add(data.toByteBuffer());
				}
				remote.send(list, sendTimeout).onDone(remote::close);
				return;
			}
			
			if (lastSend.isDone() && !lastSend.isSuccessful()) {
				closing = true;
				remote.close();
				return;
			}
			
			// add the frame to the waiting list
			synchronized (dependencyTree) {
				DependencyNode node = dependencyNodes.get(frame.getStreamId());
				if (node == null)
					node = addStreamNode(dependencyTree, frame.getStreamId(), 16);
				if (frame.getType() == HTTP2FrameHeader.TYPE_WINDOW_UPDATE)
					node.waitingFrameProducers.addFirst(frame);
				else
					node.waitingFrameProducers.addLast(frame);

				if (lastSend.isDone() || buffersReady.size() < MAX_FRAMES_PRODUCED)
					launchFrameProduction();
			}
		}
		
		// TODO when nothing to send, we may update connection window size
	}
	
	private Object frameProductionLock = new Object();
	private Task<Void, NoException> frameProductionTask = null;
	private Task<Void, NoException> nextFrameProductionTask = null;
	
	private void launchFrameProduction() {
		synchronized (frameProductionLock) {
			if (nextFrameProductionTask != null) return;
			nextFrameProductionTask = Task.cpu("Produce HTTP/2 frame", Task.Priority.RATHER_IMPORTANT, new FrameProducer());
		}
		if (frameProductionTask == null)
			nextFrameProductionTask.start();
		else
			nextFrameProductionTask.startAfter(frameProductionTask);
	}
	
	private class FrameProducer implements Executable<Void, NoException> {
		@Override
		public Void execute(Task<Void, NoException> taskContext) {
			synchronized (frameProductionLock) {
				frameProductionTask = taskContext;
				nextFrameProductionTask = null;
			}
			boolean endOfProduction = false;
			do {
				synchronized (StreamsManager.this) {
					synchronized (dependencyTree) {
						// if last send is done and something has to be sent, launch a new send
						if (lastSend.isDone() && !buffersReady.isEmpty()) {
							lastSend = new Async<>();
							lastSend.onDone(StreamsManager.this::launchFrameProduction);
							remote.newDataToSendWhenPossible(dataProvider, lastSend, sendTimeout);
						}
						if (endOfProduction || buffersReady.size() >= MAX_FRAMES_PRODUCED) {
							// end of production
							return null;
						}
					}
				}
				// produce frames
				LinkedList<HTTP2Frame.Writer> list = new LinkedList<>();
				synchronized (dependencyTree) {
					dependencyTree.removeFramesToProduce(list, MAX_FRAMES_PRODUCED - buffersReady.size());
					if (list.isEmpty()) {
						endOfProduction = true;
						continue;
					}
				}
				LinkedList<ByteBuffer> production = new LinkedList<>();
				for (HTTP2Frame.Writer producer : list) {
					do {
						int maxSize = (int)(remoteSettings != null ? remoteSettings.getMaxFrameSize()
							: HTTP2Settings.DefaultValues.MAX_FRAME_SIZE);
						ByteArray data = producer.produce(maxSize, bufferCache);
						production.add(data.toByteBuffer());
						if (producer instanceof HTTP2Data)
							decrementSendWindowSize(producer.getStreamId(),
								data.remaining() - HTTP2FrameHeader.LENGTH);
						if (logger.debug()) {
							HTTP2FrameHeader header = new HTTP2FrameHeader();
							header.createConsumer().consume(data.toByteBuffer().asReadOnlyBuffer());
							logger.debug("Frame ready to send: " + header);
						}
					} while (producer.canProduceMore() && buffersReady.size() + production.size() < MAX_FRAMES_PRODUCED);
					if (buffersReady.size() + production.size() >= MAX_FRAMES_PRODUCED)
						break;
				}

				synchronized (dependencyTree) {
					buffersReady.addAll(production);
				}
			} while (true);
		}
	}
	
	private class DataToSendProvider implements Supplier<List<ByteBuffer>> {
		@Override
		public List<ByteBuffer> get() {
			List<ByteBuffer> list = new LinkedList<>();
			synchronized (dependencyTree) {
				list.addAll(buffersReady);
				buffersReady.clear();
			}
			return list;
		}
	}
	
	void consumedConnectionRecvWindowSize(long size) {
		long increment = 0;
		synchronized (dependencyTree) {
			dependencyTree.recvWindowSize -= size;
			if (logger.debug()) logger.debug("Connection window recv consumed: " + size
				+ ", remaining = " + dependencyTree.recvWindowSize);
			if (dependencyTree.recvWindowSize <= localSettings.getWindowSize() / 2) {
				increment = localSettings.getWindowSize() - dependencyTree.recvWindowSize;
				dependencyTree.recvWindowSize += increment;
			}
		}
		if (increment > 0) {
			if (logger.debug())
				logger.debug("Connection window recv increment: " + increment);
			sendFrame(new HTTP2WindowUpdate.Writer(0, increment), false);
		}
	}
	
	void incrementConnectionSendWindowSize(long increment) {
		if (logger.debug())
			logger.debug("Increment connection send window: " + increment
				+ ", new size = " + (dependencyTree.sendWindowSize + increment));
		synchronized (dependencyTree) {
			boolean wasZero = dependencyTree.sendWindowSize <= 0;
			dependencyTree.sendWindowSize += increment;
			if (wasZero && dependencyTree.sendWindowSize > 0 && buffersReady.size() < MAX_FRAMES_PRODUCED)
				launchFrameProduction();
		}
	}
	
	void incrementStreamSendWindowSize(int streamId, long increment) {
		synchronized (dependencyTree) {
			DependencyNode node = dependencyNodes.get(streamId);
			if (node == null)
				node = addStreamNode(dependencyTree, streamId, 16);
			boolean wasZero = node.sendWindowSize <= 0;
			node.sendWindowSize += increment;
			if (logger.debug())
				logger.debug("Increment window for stream " + streamId + ": " + increment + ", new size = " + node.sendWindowSize);
			if (wasZero && node.sendWindowSize > 0 && buffersReady.size() < MAX_FRAMES_PRODUCED)
				launchFrameProduction();
		}
	}
	
	private void decrementSendWindowSize(int streamId, int size) {
		synchronized (dependencyTree) {
			dependencyTree.sendWindowSize -= size;
			DependencyNode node = dependencyNodes.get(streamId);
			if (node != null) {
				node.sendWindowSize -= size;
				if (logger.debug())
					logger.debug("Decrement window for stream " + streamId + ": " + size + ", new size = " + node.sendWindowSize);
			}
		}
	}

	
	private DataToSendProvider dataProvider = new DataToSendProvider();
	private LinkedList<ByteBuffer> buffersReady = new LinkedList<>();
	
	private final DependencyNode dependencyTree = new DependencyNode(0,
		HTTP2Settings.DefaultValues.INITIAL_WINDOW_SIZE, HTTP2Settings.DefaultValues.INITIAL_WINDOW_SIZE);
	private IntegerMap<DependencyNode> dependencyNodes = new IntegerMapRBT<DependencyNode>(10) {
		@Override
		protected int hash(int key) {
			return (key >> 1) % 10;
		}
	};
	
	private class DependencyNode {
		
		private int streamId;
		private RedBlackTreeInteger<DependencyNode> dependentStreams = new RedBlackTreeInteger<>();
		private LinkedList<HTTP2Frame.Writer> waitingFrameProducers = new LinkedList<>();
		private long sendWindowSize;
		private long recvWindowSize;
		private boolean eos = false;
		
		private DependencyNode(int streamId, long sendWindowSize, long recvWindowSize) {
			this.streamId = streamId;
			this.sendWindowSize = sendWindowSize;
			this.recvWindowSize = recvWindowSize;
		}
		
		public void endOfStream() {
			if (waitingFrameProducers.isEmpty())
				close();
			else
				eos = true;
		}
		
		private void close() {
			dependencyTree.remove(streamId);
			dependencyNodes.remove(streamId);
		}
		
		private void takeInfoFrom(DependencyNode previous) {
			for (Iterator<RedBlackTreeInteger.Node<DependencyNode>> it = previous.dependentStreams.nodeIterator(); it.hasNext(); ) {
				RedBlackTreeInteger.Node<DependencyNode> n = it.next();
				dependentStreams.add(n.getValue(), n.getElement());
			}
			waitingFrameProducers = previous.waitingFrameProducers;
			sendWindowSize = previous.sendWindowSize;
			recvWindowSize = previous.recvWindowSize;
		}
		
		private void removeFramesToProduce(List<HTTP2Frame.Writer> frames, int max) {
			int deep = 0;
			do {
				if (!removeFramesToProduce(frames, max, deep))
					return;
				if (frames.size() == max)
					return;
				deep++;
			} while (true);
		}
		
		private boolean removeFramesToProduce(List<HTTP2Frame.Writer> frames, int max, int deep) {
			if (deep == 0) {
				HTTP2WindowUpdate.Writer winUpdate = null;
				for (Iterator<HTTP2Frame.Writer> it = waitingFrameProducers.iterator(); it.hasNext(); ) {
					HTTP2Frame.Writer frame = it.next();
					if (frame instanceof HTTP2WindowUpdate.Writer) {
						// merge window updates on same stream
						if (winUpdate == null)
							winUpdate = (HTTP2WindowUpdate.Writer)frame;
						else {
							HTTP2WindowUpdate.Writer update = (HTTP2WindowUpdate.Writer)frame;
							winUpdate.setIncrement(winUpdate.getIncrement() + update.getIncrement());
							it.remove();
							continue;
						}
					}
					if (!frame.canProduceMore()) {
						it.remove();
						continue;
					}
					if (frame instanceof HTTP2Data && (dependencyTree.sendWindowSize <= 0 || sendWindowSize <= 0))
						break;
					frames.add(frame);
					if (frame.canProduceSeveralFrames())
						break;
					it.remove();
					if (frames.size() == max) {
						if (eos && waitingFrameProducers.isEmpty())
							close();
						return false;
					}
				}
				if (eos && waitingFrameProducers.isEmpty())
					close();
				return !dependentStreams.isEmpty();
			}
			boolean canHaveMore = false;
			ArrayList<DependencyNode> children = new ArrayList<>(dependentStreams.size());
			for (DependencyNode child : dependentStreams) children.add(child);
			for (DependencyNode child : children) {
				canHaveMore |= child.removeFramesToProduce(frames, max, deep - 1);
				if (frames.size() == max)
					return false;
			}
			return canHaveMore;
		}
		
		private DependencyNode remove(int id) {
			for (Iterator<RedBlackTreeInteger.Node<DependencyNode>> it = dependentStreams.nodeIterator(); it.hasNext(); ) {
				RedBlackTreeInteger.Node<DependencyNode> node = it.next();
				if (node.getElement().streamId != id)
					continue;
				// found it
				dependentStreams.remove(node);
				if (!node.getElement().dependentStreams.isEmpty()) {
					/* When a stream is removed from the dependency tree, its dependencies
   					   can be moved to become dependent on the parent of the closed stream.
   					   The weights of new dependencies are recalculated by distributing the
   					   weight of the dependency of the closed stream proportionally based on
   					   the weights of its dependencies. */
					int total = 0;
					for (Iterator<RedBlackTreeInteger.Node<DependencyNode>> itChild =
							node.getElement().dependentStreams.nodeIterator(); itChild.hasNext(); ) {
						RedBlackTreeInteger.Node<DependencyNode> child = itChild.next();
						total += child.getValue();
					}
					if (total <= 0) total = 1;
					for (Iterator<RedBlackTreeInteger.Node<DependencyNode>> itChild =
						node.getElement().dependentStreams.nodeIterator(); itChild.hasNext(); ) {
						RedBlackTreeInteger.Node<DependencyNode> child = itChild.next();
						dependentStreams.add(node.getValue() * child.getValue() / total, child.getElement());
					}
				}
				return node.getElement();
			}
			for (DependencyNode node : dependentStreams) {
				DependencyNode removed = node.remove(id);
				if (removed != null)
					return removed;
			}
			return null;
		}
		
		private void adjustSendWindowSize(long diff) {
			sendWindowSize += diff;
			for (DependencyNode node : dependentStreams)
				node.adjustSendWindowSize(diff);
		}
		
	}
	
	void addDependency(int streamId, int dependencyId, int dependencyWeight, boolean isExclusive) throws HTTP2Error {
		if (dependencyId == streamId)
			throw new HTTP2Error(false, HTTP2Error.Codes.PROTOCOL_ERROR);
		synchronized (dependencyTree) {
			DependencyNode current = dependencyNodes.get(streamId);
			DependencyNode dependency = dependencyNodes.get(dependencyId);
			if (dependency == null) {
				// stream does not exist or is closed
				if (current == null)
					addStreamNode(dependencyTree, streamId, dependencyWeight);
				return;
			}
			if (!isExclusive) {
				if (current == null)
					addStreamNode(dependency, streamId, dependencyWeight);
				else {
					dependencyTree.remove(streamId);
					DependencyNode newNode = addStreamNode(dependency, streamId, dependencyWeight);
					newNode.takeInfoFrom(current);
				}
				return;
			}
			if (current != null)
				dependencyTree.remove(streamId);
			DependencyNode newNode = new DependencyNode(streamId, remoteSettings.getWindowSize(), localSettings.getWindowSize());
			newNode.dependentStreams = dependency.dependentStreams;
			dependency.dependentStreams = new RedBlackTreeInteger<>();
			dependency.dependentStreams.add(dependencyWeight, newNode);
			if (current != null)
				newNode.takeInfoFrom(current);
			dependencyNodes.put(streamId, newNode);
		}
	}
	
	private DependencyNode addStreamNode(DependencyNode parent, int id, int weight) {
		DependencyNode node = new DependencyNode(id,
			remoteSettings != null ? remoteSettings.getWindowSize() : HTTP2Settings.DefaultValues.INITIAL_WINDOW_SIZE,
			localSettings.getWindowSize());
		parent.dependentStreams.add(weight, node);
		dependencyNodes.put(id, node);
		return node;
	}

}
