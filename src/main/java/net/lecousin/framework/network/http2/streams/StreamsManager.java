package net.lecousin.framework.network.http2.streams;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.lecousin.framework.collections.map.IntegerMap;
import net.lecousin.framework.collections.map.IntegerMapRBT;
import net.lecousin.framework.collections.sort.RedBlackTreeInteger;
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
	private Map<Integer, StreamHandler> dataStreams = new HashMap<>(10);
	private int lastRemoteStreamId = 0;
	private int lastLocalStreamId = -1;
	private boolean closing = false;

	HPackDecompress decompressionContext;
	HPackCompress compressionContext;
	int currentDecompressionStreamId = -1;
	private int currentCompressionStreamId = -1;
	private LinkedList<Pair<Integer, AsyncSupplier<Pair<HPackCompress, Integer>, NoException>>> compressionContextRequests = new LinkedList<>();
	
	private long connectionRecvWindowSize;
	
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
			dependencyTree.windowSize = remoteSettings.getWindowSize();
		}
		connectionRecvWindowSize = localSettings.getWindowSize();
		
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
	
	Logger getLogger() {
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
					dependencyTree.adjustWindowSize(diff);
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
			if ((currentFrameHeader.getStreamId() % 2) == (clientMode ? 0 : 1)) {
				connectionError(HTTP2Error.Codes.PROTOCOL_ERROR, null);
				return null;
			}
			stream = dataStreams.get(Integer.valueOf(currentFrameHeader.getStreamId() >> 1));
			if (stream == null) {
				if (currentFrameHeader.getStreamId() <= lastRemoteStreamId) {
					// it may be remaining frames sent before we sent a reset stream to the remote
					// we need to process it as it may change connection contexts (i.e. decompression context)
					switch (currentFrameHeader.getType()) {
					case HTTP2FrameHeader.TYPE_HEADERS:
					case HTTP2FrameHeader.TYPE_CONTINUATION:
						stream = new SkipHeadersFrame(currentFrameHeader.getStreamId());
						break;
					case HTTP2FrameHeader.TYPE_DATA:
					case HTTP2FrameHeader.TYPE_RST_STREAM:
					case HTTP2FrameHeader.TYPE_WINDOW_UPDATE:
						stream = new SkipFrame();
						break;
					case HTTP2FrameHeader.TYPE_PRIORITY:
						// TODO
						stream = new SkipFrame();
						break;
					default:
						// The identifier of a newly established stream MUST be numerically
						// greater than all streams that the initiating endpoint has opened or reserved.
						connectionError(HTTP2Error.Codes.PROTOCOL_ERROR, null);
						return null;
					}
				} else {
					// TODO check if maximum is reached
					stream = openStream(currentFrameHeader.getStreamId());
				}
			}
		}
		if (!stream.startFrame(this, currentFrameHeader))
			return null;
		return stream;
	}
	
	private DataStreamHandler openStream(int id) {
		lastRemoteStreamId = id;
		DataStreamHandler stream = new DataStreamHandler(id);
		dataStreams.put(Integer.valueOf(id >> 1), stream);
		synchronized (dependencyTree) {
			addStreamNode(dependencyTree, id, 16);
		}
		return stream;
	}
	
	void closeStream(DataStreamHandler stream) {
		dataStreams.remove(Integer.valueOf(stream.getStreamId() >> 1));
	}
	
	public void endOfStream(int streamId) {
		synchronized (dependencyTree) {
			dependencyTree.remove(streamId);
			dependencyNodes.remove(streamId >> 1);
		}
	}
	
	void consumedConnectionRecvWindowSize(long size) {
		connectionRecvWindowSize -= size;
		if (connectionRecvWindowSize <= localSettings.getWindowSize() / 2) {
			long increment = localSettings.getWindowSize() - connectionRecvWindowSize;
			connectionRecvWindowSize += increment;
			sendFrame(new HTTP2WindowUpdate.Writer(0, increment), false);
		}
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
				lastLocalStreamId += 2;
				currentCompressionStreamId = lastLocalStreamId;
				return new AsyncSupplier<>(new Pair<>(compressionContext, Integer.valueOf(lastLocalStreamId)), null);
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
				lastLocalStreamId += 2;
				currentCompressionStreamId = lastLocalStreamId;
			} else {
				currentCompressionStreamId = request.getValue1().intValue();
			}
			request.getValue2().unblockSuccess(new Pair<>(compressionContext, Integer.valueOf(currentCompressionStreamId)));
		}
	}
	
	private Async<IOException> lastSend = new Async<>(true);
	private Async<NoException> frameProduction = new Async<>(true);
	private static final int MAX_FRAMES_PRODUCED = 5;
	
	public void sendFrame(HTTP2Frame.Writer frame, boolean closeAfter) {
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
				DependencyNode node = dependencyNodes.get(frame.getStreamId() / 2);
				if (node == null)
					node = addStreamNode(dependencyTree, frame.getStreamId(), 16);
				node.waitingFrameProducers.add(frame);

				if ((lastSend.isDone() && frameProduction.isDone()) || // nothing current done
					(frameProduction.isDone() && buffersReady.size() < MAX_FRAMES_PRODUCED))
					launchFrameProduction();
			}
		}
		
		// TODO when nothing to send, we may update connection window size
	}
	
	private void launchFrameProduction() {
		frameProduction = new Async<>();
		Task.cpu("Produce HTTP/2 frame", Task.Priority.RATHER_IMPORTANT, t -> {
			do {
				// produce frames
				LinkedList<HTTP2Frame.Writer> list = new LinkedList<>();
				synchronized (dependencyTree) {
					if (buffersReady.size() >= MAX_FRAMES_PRODUCED) {
						frameProduction.unblock();
						return null;
					}
					dependencyTree.removeFramesToProduce(list, MAX_FRAMES_PRODUCED - buffersReady.size());
					if (list.isEmpty()) {
						frameProduction.unblock();
						return null;
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
							windowSizeUsed(producer.getStreamId(), data.remaining());
						if (logger.debug()) {
							HTTP2FrameHeader header = new HTTP2FrameHeader();
							header.createConsumer().consume(data.toByteBuffer().asReadOnlyBuffer());
							logger.debug("Frame to send: " + header);
						}
					} while (producer.canProduceMore() && buffersReady.size() + production.size() < MAX_FRAMES_PRODUCED);
					if (buffersReady.size() + production.size() >= MAX_FRAMES_PRODUCED)
						break;
				}

				synchronized (dependencyTree) {
					buffersReady.addAll(production);
				}
				// if nothing currently sent, launch a new send
				synchronized (StreamsManager.this) {
					if (lastSend.isDone()) {
						lastSend = new Async<>();
						List<ByteBuffer> buffers = new LinkedList<>();
						buffersSent = buffers;
						remote.newDataToSendWhenPossible(dataProvider, lastSend, sendTimeout);
					}
				}
			} while (true);
		}).start();
	}
	
	private class DataToSendProvider implements Supplier<List<ByteBuffer>> {
		@Override
		public List<ByteBuffer> get() {
			List<ByteBuffer> list = new LinkedList<>();
			synchronized (dependencyTree) {
				list.addAll(buffersReady);
				buffersSent.addAll(buffersReady);
				buffersReady.clear();
				if (frameProduction.isDone())
					launchFrameProduction();
			}
			return list;
		}
	}
	
	
	void incrementConnectionSendWindowSize(long increment) {
		synchronized (dependencyTree) {
			boolean wasZero = dependencyTree.windowSize <= 0;
			dependencyTree.windowSize += increment;
			if (wasZero && dependencyTree.windowSize > 0 && frameProduction.isDone() && buffersReady.size() < MAX_FRAMES_PRODUCED)
				launchFrameProduction();
		}
	}
	
	void incrementStreamSendWindowSize(int streamId, long increment) {
		synchronized (dependencyTree) {
			DependencyNode node = dependencyNodes.get(streamId / 2);
			if (node == null)
				node = addStreamNode(dependencyTree, streamId, 16);
			boolean wasZero = node.windowSize <= 0;
			node.windowSize += increment;
			if (wasZero && node.windowSize > 0 && frameProduction.isDone() && buffersReady.size() < MAX_FRAMES_PRODUCED)
				launchFrameProduction();
		}
	}
	
	private void windowSizeUsed(int streamId, int size) {
		synchronized (dependencyTree) {
			dependencyTree.windowSize -= size;
			DependencyNode node = dependencyNodes.get(streamId / 2);
			if (node != null)
				node.windowSize -= size;
		}
	}

	
	private DataToSendProvider dataProvider = new DataToSendProvider();
	private List<ByteBuffer> buffersSent = new LinkedList<>();
	private LinkedList<ByteBuffer> buffersReady = new LinkedList<>();
	
	private DependencyNode dependencyTree = new DependencyNode(0, HTTP2Settings.DefaultValues.INITIAL_WINDOW_SIZE);
	private IntegerMap<DependencyNode> dependencyNodes = new IntegerMapRBT<>(10);
	
	private class DependencyNode {
		
		private int streamId;
		private RedBlackTreeInteger<DependencyNode> dependentStreams = new RedBlackTreeInteger<>();
		private LinkedList<HTTP2Frame.Writer> waitingFrameProducers = new LinkedList<>();
		private long windowSize;
		
		private DependencyNode(int streamId, long windowSize) {
			this.streamId = streamId;
			this.windowSize = windowSize;
		}
		
		private void takeInfoFrom(DependencyNode previous) {
			for (Iterator<RedBlackTreeInteger.Node<DependencyNode>> it = previous.dependentStreams.nodeIterator(); it.hasNext(); ) {
				RedBlackTreeInteger.Node<DependencyNode> n = it.next();
				dependentStreams.add(n.getValue(), n.getElement());
			}
			waitingFrameProducers = previous.waitingFrameProducers;
			windowSize = previous.windowSize;
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
				for (Iterator<HTTP2Frame.Writer> it = waitingFrameProducers.iterator(); it.hasNext(); ) {
					HTTP2Frame.Writer frame = it.next();
					if (!frame.canProduceMore()) {
						it.remove();
						continue;
					}
					if (frame instanceof HTTP2Data && (dependencyTree.windowSize <= 0 || windowSize <= 0))
						break;
					frames.add(frame);
					if (frame.canProduceSeveralFrames())
						break;
					it.remove();
					if (frames.size() == max)
						return false;
				}
				return !dependentStreams.isEmpty();
			}
			boolean canHaveMore = false;
			for (DependencyNode child : dependentStreams) {
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
				it.remove();
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
		
		private void adjustWindowSize(long diff) {
			windowSize += diff;
			for (DependencyNode node : dependentStreams)
				node.adjustWindowSize(diff);
		}
		
	}
	
	void addDependency(int streamId, int dependencyId, int dependencyWeight, boolean isExclusive) throws HTTP2Error {
		if (dependencyId == streamId)
			throw new HTTP2Error(false, HTTP2Error.Codes.PROTOCOL_ERROR);
		synchronized (dependencyTree) {
			DependencyNode current = dependencyNodes.get(streamId / 2);
			DependencyNode dependency = dependencyNodes.get(dependencyId / 2);
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
			DependencyNode newNode = new DependencyNode(streamId, remoteSettings.getWindowSize());
			newNode.dependentStreams = dependency.dependentStreams;
			dependency.dependentStreams = new RedBlackTreeInteger<>();
			dependency.dependentStreams.add(dependencyWeight, newNode);
			if (current != null)
				newNode.takeInfoFrom(current);
			dependencyNodes.put(streamId / 2, newNode);
		}
	}
	
	private DependencyNode addStreamNode(DependencyNode parent, int id, int weight) {
		DependencyNode node = new DependencyNode(id,
			remoteSettings != null ? remoteSettings.getWindowSize() : HTTP2Settings.DefaultValues.INITIAL_WINDOW_SIZE);
		parent.dependentStreams.add(weight, node);
		dependencyNodes.put(id / 2, node);
		return node;
	}

}
