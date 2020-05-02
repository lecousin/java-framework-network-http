package net.lecousin.framework.network.http2.streams;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
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
import net.lecousin.framework.network.http2.frame.HTTP2Ping;
import net.lecousin.framework.network.http2.frame.HTTP2Settings;
import net.lecousin.framework.network.http2.frame.HTTP2WindowUpdate;
import net.lecousin.framework.network.http2.hpack.HPackCompress;
import net.lecousin.framework.network.http2.hpack.HPackDecompress;
import net.lecousin.framework.util.Pair;

public abstract class StreamsManager {
	
	protected TCPRemote remote;
	private boolean clientMode;
	protected HTTP2Settings localSettings;
	protected HTTP2Settings remoteSettings;
	private Async<IOException> connectionReady = new Async<>();
	private int sendTimeout;
	protected Logger logger;
	protected ByteArrayCache bufferCache;
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
	private LinkedList<Pair<Integer, AsyncSupplier<Pair<HPackCompress, Integer>, IOException>>> compressionContextRequests = new LinkedList<>();
	
	private FrameState currentFrameState = FrameState.START;
	private HTTP2FrameHeader currentFrameHeader = null;
	private HTTP2FrameHeader.Consumer currentFrameHeaderConsumer = null;
	private StreamHandler currentStream = null;
	
	private List<Pair<byte[], Runnable>> pings = new LinkedList<>();
	
	public StreamsManager(
		TCPRemote remote, boolean clientMode,
		HTTP2Settings localSettings, boolean localSettingsSent, HTTP2Settings initialRemoteSettings,
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
		if (!localSettingsSent)
			sendFrame(new HTTP2Settings.Writer(localSettings, true), false);
		
		if (remoteSettings != null) {
			// send ACK
			sendFrame(new HTTP2Frame.EmptyWriter(
				new HTTP2FrameHeader(0, HTTP2FrameHeader.TYPE_SETTINGS, HTTP2Settings.FLAG_ACK, 0)), false);
			connectionReady.unblock();
		}

		remote.onclosed(() -> Task.cpu("Closing HTTP/2 streams", Priority.NORMAL, t -> {
			close();
			return null;
		}).start());
	}
	
	public boolean isClientMode() {
		return clientMode;
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
	
	public void close() {
		closing = true;
		if (logger.debug())
			logger.debug("Remote closed - clean HTTP/2 streams");
		ClosedChannelException closed = new ClosedChannelException();
		synchronized (compressionContextRequests) {
			while (!compressionContextRequests.isEmpty())
				compressionContextRequests.removeFirst().getValue2().error(closed);
		}
		synchronized (dependencyTree) {
			for (Pair<ByteBuffer, Async<IOException>> p : buffersReady)
				if (p.getValue2() != null)
					p.getValue2().error(closed);
			dependencyTree.forceClose(closed);
			dependencyNodes.clear();
		}
		List<StreamHandler> handlers;
		synchronized (dataStreams) {
			handlers = new ArrayList<>(dataStreams.size());
			for (Iterator<StreamHandler> it = dataStreams.values(); it.hasNext(); )
				handlers.add(it.next());
			dataStreams.clear();
		}
		for (StreamHandler handler : handlers)
			handler.closed();
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
		Task.cpu("Continue consuming HTTP/2 data", t -> {
			consumeDataFromRemote(data, onConsumed);
			return null;
		}).start();
	}

	void applyRemoteSettings(HTTP2Settings settings) {
		if (remoteSettings == null) {
			remoteSettings = settings;
			decompressionContext = new HPackDecompress((int)settings.getHeaderTableSize());
			// send ACK
			sendFrame(new HTTP2Frame.EmptyWriter(
				new HTTP2FrameHeader(0, HTTP2FrameHeader.TYPE_SETTINGS, HTTP2Settings.FLAG_ACK, 0)), false);
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
		if (logger.error())
			logger.error("Send connection error " + errorCode + ": " + debugMessage);
		HTTP2GoAway.Writer frame = new HTTP2GoAway.Writer(lastRemoteStreamId, errorCode,
			debugMessage != null ? debugMessage.getBytes(StandardCharsets.UTF_8) : null);
		sendFrame(frame, true);
	}
	
	public void sendPing(byte[] data, Runnable onResponseReceived) {
		synchronized (pings) {
			pings.add(new Pair<>(data, onResponseReceived));
		}
		sendFrame(new HTTP2Ping.Writer(data, false), false);
	}
	
	void pingResponse(byte[] data) {
		Runnable listener = null;
		synchronized (pings) {
			for (Iterator<Pair<byte[], Runnable>> it = pings.iterator(); it.hasNext(); ) {
				Pair<byte[], Runnable> p = it.next();
				if (Arrays.equals(data, p.getValue1())) {
					listener = p.getValue2();
					it.remove();
					break;
				}
			}
		}
		if (listener != null)
			listener.run();
	}
	
	/** Start processing a frame, return the stream handler or null to stop everything. */
	private StreamHandler processFrameHeader() {
		if (closing)
			return null;
		if (logger.trace())
			logger.trace("Received frame: " + currentFrameHeader);
		if (currentFrameHeader.getPayloadLength() > localSettings.getMaxFrameSize()) {
			connectionError(HTTP2Error.Codes.PROTOCOL_ERROR, "Your frame is larger than what i asked you");
			return null;
		}
		StreamHandler stream;
		if (currentFrameHeader.getStreamId() == 0) {
			stream = connectionStream;
		} else {
			// client stream identifiers MUST be odd, server stream even
			synchronized (dataStreams) {
				stream = dataStreams.get(currentFrameHeader.getStreamId());
			}
			if (stream == null) {
				// it may be a request to open a new stream, or remaining frames on a previous stream
				if ((currentFrameHeader.getStreamId() % 2) == (clientMode ? 1 : 0) ||
					(currentFrameHeader.getStreamId() <= lastRemoteStreamId)) {
					// it may be remaining frames sent before we sent a reset stream to the remote
					// we need to process it as it may change connection contexts (i.e. decompression context)
					stream = handleFrameOnClosedStream();
				} else {
					// this is a new stream from the remote
					stream = openRemoteStream(currentFrameHeader.getStreamId());
				}
			}
		}
		if (stream != null && !stream.startFrame(this, currentFrameHeader))
			return null;
		return stream;
	}
	
	private StreamHandler handleFrameOnClosedStream() {
		switch (currentFrameHeader.getType()) {
		case HTTP2FrameHeader.TYPE_HEADERS:
		case HTTP2FrameHeader.TYPE_CONTINUATION:
			if (logger.trace())
				logger.trace("Received headers frame on closed stream, process it: " + currentFrameHeader);
			return new SkipHeadersFrame(currentFrameHeader.getStreamId());
		case HTTP2FrameHeader.TYPE_PRIORITY:
			// TODO
		case HTTP2FrameHeader.TYPE_DATA:
		case HTTP2FrameHeader.TYPE_RST_STREAM:
			if (logger.trace())
				logger.trace("Received frame on closed stream, skip it: " + currentFrameHeader);
			return new SkipFrame(currentFrameHeader.getStreamId());
		case HTTP2FrameHeader.TYPE_WINDOW_UPDATE:
			DependencyNode node;
			synchronized (dependencyTree) {
				node = dependencyNodes.get(currentFrameHeader.getStreamId());
			}
			if (node == null) {
				if (logger.trace())
					logger.trace("Received window update on closed stream, skip it: "
						+ currentFrameHeader);
				return new SkipFrame(currentFrameHeader.getStreamId());
			}
			if (logger.trace())
				logger.trace("Received window update on closed stream with pending data, process it: "
					+ currentFrameHeader);
			return new ProcessWindowUpdateFrame(currentFrameHeader.getStreamId());
		default:
			// The identifier of a newly established stream MUST be numerically
			// greater than all streams that the initiating endpoint has opened or reserved.
			connectionError(HTTP2Error.Codes.PROTOCOL_ERROR, "Invalid stream id");
			return null;
		}
	}
	
	public int getLastLocalStreamId() {
		return lastLocalStreamId;
	}
	
	public int getLastRemoteStreamId() {
		return lastRemoteStreamId;
	}
	
	private DataStreamHandler openRemoteStream(int id) {
		lastRemoteStreamId = id;
		DataStreamHandler stream = new DataStreamHandler(id);
		synchronized (dataStreams) {
			dataStreams.put(id, stream);
		}
		for (int trial = 0; true; trial++) {
			boolean tooManyStreams = false;
			synchronized (dependencyTree) {
				// check if maximum is reached
				if (localSettings.getMaxConcurrentStreams() > 0 && dependencyNodes.size() > localSettings.getMaxConcurrentStreams()) {
					// we may need to close stream
					dependencyTree.cleanStreams();
					if (dependencyNodes.size() > localSettings.getMaxConcurrentStreams()) {
						tooManyStreams = true;
					}
				}
				if (!tooManyStreams)
					addStreamNode(dependencyTree, id, 16);
			}
			if (tooManyStreams) {
				Task<Void, NoException> task = null;
				synchronized (endOfStreamTasks) {
					if (!endOfStreamTasks.isEmpty())
						task = endOfStreamTasks.get(0);
				}
				if (trial < 10) {
					if (task != null)
						task.getOutput().block(1000);
					else
						new Async<>().block(100); // TODO we should be able to check what can be closed
					continue;
				}
				connectionError(HTTP2Error.Codes.ENHANCE_YOUR_CALM, "Too many open streams ("
					+ dependencyNodes.size() + "/" + localSettings.getMaxConcurrentStreams() + ")");
				return null;
			}
			return stream;
		}
	}
	
	private int openLocalStream() {
		synchronized (dependencyTree) {
			// check we did not reach the maximum specified by the remote
			if (remoteSettings.getMaxConcurrentStreams() > 0 && dependencyNodes.size() > remoteSettings.getMaxConcurrentStreams())
				return -1;
			lastLocalStreamId += 2;
			DataStreamHandler stream = new DataStreamHandler(lastLocalStreamId);
			synchronized (dataStreams) {
				dataStreams.put(lastLocalStreamId, stream);
			}
			addStreamNode(dependencyTree, lastLocalStreamId, 16);
		}
		return lastLocalStreamId;
	}
	
	public AsyncSupplier<HPackCompress, IOException> reserveCompressionContext(int streamId) {
		synchronized (compressionContextRequests) {
			if (currentCompressionStreamId == -1) {
				currentCompressionStreamId = streamId;
				return new AsyncSupplier<>(compressionContext, null);
			}
			AsyncSupplier<HPackCompress, IOException> result = new AsyncSupplier<>();
			AsyncSupplier<Pair<HPackCompress, Integer>, IOException> request = new AsyncSupplier<>();
			compressionContextRequests.add(new Pair<>(Integer.valueOf(streamId), request));
			request.onDone(p -> result.unblockSuccess(p.getValue1()), result);
			return result;
		}
	}

	public AsyncSupplier<Pair<HPackCompress, Integer>, IOException> reserveCompressionContextAndOpenStream() {
		synchronized (compressionContextRequests) {
			if (closing)
				return new AsyncSupplier<>(null, new ClosedChannelException());
			if (currentCompressionStreamId == -1) {
				currentCompressionStreamId = openLocalStream();
				if (currentCompressionStreamId != -1)
					return new AsyncSupplier<>(new Pair<>(compressionContext, Integer.valueOf(currentCompressionStreamId)), null);
			}
			AsyncSupplier<Pair<HPackCompress, Integer>, IOException> request = new AsyncSupplier<>();
			compressionContextRequests.add(new Pair<>(null, request));
			return request;
		}
	}
	
	public void releaseCompressionContext(int streamId) {
		Pair<Integer, AsyncSupplier<Pair<HPackCompress, Integer>, IOException>> request;
		synchronized (compressionContextRequests) {
			if (currentCompressionStreamId != streamId)
				return;
			request = compressionContextRequests.poll();
			if (request == null) {
				currentCompressionStreamId = -1;
				return;
			}
			if (request.getValue1() == null) {
				currentCompressionStreamId = openLocalStream();
				if (currentCompressionStreamId == -1) {
					compressionContextRequests.addFirst(request);
					return;
				}
			} else {
				currentCompressionStreamId = request.getValue1().intValue();
			}
		}
		request.getValue2().unblockSuccess(new Pair<>(compressionContext, Integer.valueOf(currentCompressionStreamId)));
	}
	
	@SuppressWarnings("squid:S3398") // better readability to keep this method here
	private void streamClosed() {
		if (closing)
			return;
		Task.cpu("Check pending requests to open stream", Priority.NORMAL, t -> {
			Pair<Integer, AsyncSupplier<Pair<HPackCompress, Integer>, IOException>> request = null;
			synchronized (compressionContextRequests) {
				if (currentCompressionStreamId == -1 && !compressionContextRequests.isEmpty()) {
					request = compressionContextRequests.getFirst();
					if (request == null || request.getValue1() != null)
						return null;
					currentCompressionStreamId = openLocalStream();
					if (currentCompressionStreamId == -1)
						return null;
					compressionContextRequests.removeFirst();
				}
			}
			if (request != null)
				request.getValue2().unblockSuccess(new Pair<>(compressionContext, Integer.valueOf(currentCompressionStreamId)));
			return null;
		}).start();
	}
	
	protected abstract void streamError(int streamId, int error);
	
	void closeStream(DataStreamHandler stream, int error) {
		if (error != 0)
			streamError(stream.getStreamId(), error);
		synchronized (dataStreams) {
			dataStreams.remove(stream.getStreamId());
		}
	}
	
	private List<Task<Void, NoException>> endOfStreamTasks = new LinkedList<>();
	
	public void taskEndOfStream(int streamId) {
		Task<Void, NoException> task = Task.cpu("Close HTTP/2 stream", Priority.RATHER_IMPORTANT, t -> {
			endOfStream(streamId);
			synchronized (endOfStreamTasks) {
				endOfStreamTasks.remove(t);
			}
			return null;
		});
		synchronized (endOfStreamTasks) {
			endOfStreamTasks.add(task);
		}
		task.start();
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
	
	public IAsync<IOException> sendFrame(HTTP2Frame.Writer frame, boolean closeAfter) {
		if (logger.trace()) logger.trace("Frame to send on stream " + frame.getStreamId()
			+ ": " + HTTP2FrameHeader.getTypeName(frame.getType()));
		if (closing)
			return new Async<>(new ClosedChannelException());
		
		// if last frame, this is a connection error, we can send it as soon as possible
		if (closeAfter) {
			closing = true;
			LinkedList<ByteBuffer> list = new LinkedList<>();
			while (frame.canProduceMore()) {
				ByteArray data = frame.produce((int)remoteSettings.getMaxFrameSize(), bufferCache);
				list.add(data.toByteBuffer());
			}
			IAsync<IOException> send = remote.send(list, sendTimeout);
			send.onDone(remote::close);
			return send;
		}
		
		// add the frame to the waiting list
		Async<IOException> sp = new Async<>();
		synchronized (dependencyTree) {
			DependencyNode node = dependencyNodes.get(frame.getStreamId());
			if (node == null)
				node = addStreamNode(dependencyTree, frame.getStreamId(), 16);
			if (frame.getType() == HTTP2FrameHeader.TYPE_WINDOW_UPDATE || frame.getType() == HTTP2FrameHeader.TYPE_PING)
				node.waitingFrameProducers.addFirst(new Pair<>(frame, sp));
			else
				node.waitingFrameProducers.addLast(new Pair<>(frame, sp));

			if (lastSend.isDone() || buffersReady.size() < MAX_FRAMES_PRODUCED)
				launchFrameProduction();
		}
		return sp;
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
				synchronized (dependencyTree) {
					// if last send is done and something has to be sent, launch a new send
					if (lastSend.isDone() && !buffersReady.isEmpty())
						launchSend();
					if (endOfProduction || buffersReady.size() >= MAX_FRAMES_PRODUCED) {
						// end of production
						return null;
					}
				}
				// produce frames
				LinkedList<Pair<HTTP2Frame.Writer, Async<IOException>>> list = new LinkedList<>();
				synchronized (dependencyTree) {
					dependencyTree.removeFramesToProduce(list, MAX_FRAMES_PRODUCED - buffersReady.size());
				}
				if (list.isEmpty()) {
					endOfProduction = true;
					continue;
				}
				produceFrames(list);
			} while (true);
		}
		
		private void launchSend() {
			lastSend = new Async<>();
			lastSend.onSuccess(StreamsManager.this::launchFrameProduction);
			lastSend.onError(e -> {
				closing = true;
				if (e instanceof ClosedChannelException)
					return;
				logger.error("Error sending frames on " + remote, e);
				remote.close();
			});
			remote.newDataToSendWhenPossible(dataProvider, lastSend, sendTimeout);
		}
		
		private void produceFrames(LinkedList<Pair<HTTP2Frame.Writer, Async<IOException>>> list) {
			LinkedList<Pair<ByteBuffer, Async<IOException>>> production = new LinkedList<>();
			for (Pair<HTTP2Frame.Writer, Async<IOException>> frame : list) {
				HTTP2Frame.Writer producer = frame.getValue1();
				do {
					int maxSize = (int)(remoteSettings != null ? remoteSettings.getMaxFrameSize()
						: HTTP2Settings.DefaultValues.MAX_FRAME_SIZE);
					ByteArray data = producer.produce(maxSize, bufferCache);
					production.add(new Pair<>(data.toByteBuffer(), producer.canProduceMore() ? null : frame.getValue2()));
					if (producer instanceof HTTP2Data)
						decrementSendWindowSize(producer.getStreamId(),
							data.remaining() - HTTP2FrameHeader.LENGTH);
					if (logger.trace()) {
						HTTP2FrameHeader header = new HTTP2FrameHeader();
						header.createConsumer().consume(data.toByteBuffer().asReadOnlyBuffer());
						logger.trace("Frame ready to send: " + header);
					}
				} while (producer.canProduceMore() && buffersReady.size() + production.size() < MAX_FRAMES_PRODUCED);
				if (buffersReady.size() + production.size() >= MAX_FRAMES_PRODUCED)
					break;
			}

			synchronized (dependencyTree) {
				buffersReady.addAll(production);
			}
		}
	}
	
	private class DataToSendProvider implements Supplier<List<ByteBuffer>> {
		@Override
		public List<ByteBuffer> get() {
			List<ByteBuffer> list = new LinkedList<>();
			List<Async<IOException>> sp = new LinkedList<>();
			synchronized (dependencyTree) {
				for (Pair<ByteBuffer, Async<IOException>> p : buffersReady) {
					list.add(p.getValue1());
					if (p.getValue2() != null)
						sp.add(p.getValue2());
				}
				buffersReady.clear();
			}
			for (Async<IOException> done : sp)
				done.unblock();
			return list;
		}
	}
	
	void consumedConnectionRecvWindowSize(long size) {
		long increment = 0;
		synchronized (dependencyTree) {
			dependencyTree.recvWindowSize -= size;
			if (logger.trace()) logger.trace("Connection window recv consumed: " + size
				+ ", remaining = " + dependencyTree.recvWindowSize);
			if (dependencyTree.recvWindowSize <= localSettings.getWindowSize() / 2) {
				increment = localSettings.getWindowSize() - dependencyTree.recvWindowSize;
				dependencyTree.recvWindowSize += increment;
			}
		}
		if (increment > 0) {
			if (logger.trace())
				logger.trace("Connection window recv increment: " + increment);
			sendFrame(new HTTP2WindowUpdate.Writer(0, increment), false);
		}
	}
	
	void incrementConnectionSendWindowSize(long increment) {
		if (logger.trace())
			logger.trace("Increment connection send window by " + increment + " => " + (dependencyTree.sendWindowSize + increment));
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
			if (logger.trace())
				logger.trace("Increment window for stream " + streamId + ": " + increment + ", new size = " + node.sendWindowSize);
			if (wasZero && node.sendWindowSize > 0 && buffersReady.size() < MAX_FRAMES_PRODUCED)
				launchFrameProduction();
		}
	}
	
	@SuppressWarnings("java:S3398") // better organized to keep this method here
	private void decrementSendWindowSize(int streamId, int size) {
		synchronized (dependencyTree) {
			dependencyTree.sendWindowSize -= size;
			DependencyNode node = dependencyNodes.get(streamId);
			if (node != null) {
				node.sendWindowSize -= size;
				if (logger.trace())
					logger.trace("Decrement window for stream " + streamId + ": " + size + ", new size = " + node.sendWindowSize);
			}
		}
	}

	
	private DataToSendProvider dataProvider = new DataToSendProvider();
	private LinkedList<Pair<ByteBuffer, Async<IOException>>> buffersReady = new LinkedList<>();
	
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
		private RedBlackTreeInteger<List<DependencyNode>> dependentStreams = new RedBlackTreeInteger<>();
		private LinkedList<Pair<HTTP2Frame.Writer, Async<IOException>>> waitingFrameProducers = new LinkedList<>();
		private long sendWindowSize;
		private long recvWindowSize;
		private boolean eos = false;
		
		private DependencyNode(int streamId, long sendWindowSize, long recvWindowSize) {
			this.streamId = streamId;
			this.sendWindowSize = sendWindowSize;
			this.recvWindowSize = recvWindowSize;
		}
		
		public void endOfStream() {
			eos = true;
			if (waitingFrameProducers.isEmpty())
				close();
		}
		
		private void close() {
			dependencyTree.remove(streamId);
			dependencyNodes.remove(streamId);
			streamClosed();
		}
		
		private void cleanStreams() {
			if (eos && waitingFrameProducers.isEmpty())
				close();
			LinkedList<DependencyNode> children = new LinkedList<>();
			for (Iterator<List<DependencyNode>> it = dependentStreams.orderedIterator(); it.hasNext(); )
				children.addAll(it.next());
			for (DependencyNode child : children)
				child.cleanStreams();
		}
		
		private void forceClose(IOException error) {
			for (Pair<HTTP2Frame.Writer, Async<IOException>> p : waitingFrameProducers)
				if (p.getValue2() != null)
					p.getValue2().error(error);
			waitingFrameProducers.clear();
			eos = true;
			LinkedList<DependencyNode> children = new LinkedList<>();
			for (Iterator<List<DependencyNode>> it = dependentStreams.orderedIterator(); it.hasNext(); )
				children.addAll(it.next());
			for (DependencyNode child : children)
				child.forceClose(error);
			close();
		}
		
		private void takeInfoFrom(DependencyNode previous) {
			for (Iterator<RedBlackTreeInteger.Node<List<DependencyNode>>> it = previous.dependentStreams.nodeIterator(); it.hasNext(); ) {
				RedBlackTreeInteger.Node<List<DependencyNode>> n = it.next();
				List<DependencyNode> list = dependentStreams.get(n.getValue());
				if (list == null) {
					list = new LinkedList<>();
					dependentStreams.add(n.getValue(), list);
				}
				list.addAll(n.getElement());
			}
			waitingFrameProducers = previous.waitingFrameProducers;
			sendWindowSize = previous.sendWindowSize;
			recvWindowSize = previous.recvWindowSize;
		}
		
		private void removeFramesToProduce(List<Pair<HTTP2Frame.Writer, Async<IOException>>> frames, int max) {
			int deep = 0;
			while (max > frames.size()) {
				LinkedList<DependencyNode> toClose = new LinkedList<>();
				if (!removeFramesToProduce(frames, max, deep, toClose))
					return;
				if (!toClose.isEmpty()) {
					for (DependencyNode node : toClose)
						node.close();
					if (deep == 0)
						deep++;
					continue;
				}
				deep++;
			}
		}
		
		private boolean removeFramesToProduce(
			List<Pair<HTTP2Frame.Writer, Async<IOException>>> frames, int max, int deep, List<DependencyNode> toClose
		) {
			if (deep == 0)
				return removeFramesToProduceAtThisLevel(frames, max, toClose);
			boolean canHaveMore = false;
			LinkedList<DependencyNode> children = new LinkedList<>();
			for (Iterator<List<DependencyNode>> it = dependentStreams.orderedIterator(); it.hasNext(); )
				children.addAll(it.next());
			for (DependencyNode child : children) {
				canHaveMore |= child.removeFramesToProduce(frames, max, deep - 1, toClose);
				if (frames.size() == max)
					return false;
			}
			return canHaveMore;
		}
		
		private boolean removeFramesToProduceAtThisLevel(
			List<Pair<HTTP2Frame.Writer, Async<IOException>>> frames, int max, List<DependencyNode> toClose
		) {
			HTTP2WindowUpdate.Writer winUpdate = null;
			for (Iterator<Pair<HTTP2Frame.Writer, Async<IOException>>> it = waitingFrameProducers.iterator(); it.hasNext(); ) {
				Pair<HTTP2Frame.Writer, Async<IOException>> p = it.next();
				HTTP2Frame.Writer frame = p.getValue1();
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
				frames.add(p);
				if (frame.canProduceSeveralFrames())
					break;
				it.remove();
				if (frames.size() == max) {
					if (eos && waitingFrameProducers.isEmpty())
						toClose.add(this);
					return false;
				}
			}
			if (eos && waitingFrameProducers.isEmpty())
				toClose.add(this);
			return !dependentStreams.isEmpty();
		}
		
		private DependencyNode remove(int id) {
			for (Iterator<RedBlackTreeInteger.Node<List<DependencyNode>>> it = dependentStreams.nodeIterator(); it.hasNext(); ) {
				RedBlackTreeInteger.Node<List<DependencyNode>> nodes = it.next();
				for (Iterator<DependencyNode> it2 = nodes.getElement().iterator(); it2.hasNext(); ) {
					DependencyNode n = it2.next();
					if (n.streamId != id)
						continue;
					// found it
					it2.remove();
					if (nodes.getElement().isEmpty())
						dependentStreams.remove(nodes);
					if (!n.dependentStreams.isEmpty())
						moveDependenciesToParent(n, nodes.getValue());
					return n;
				}
			}
			for (List<DependencyNode> children : dependentStreams) {
				for (DependencyNode child : children) {
					DependencyNode removed = child.remove(id);
					if (removed != null)
						return removed;
				}
			}
			return null;
		}
		
		private void moveDependenciesToParent(DependencyNode parent, int parentWeight) {
			/* When a stream is removed from the dependency tree, its dependencies
			   can be moved to become dependent on the parent of the closed stream.
			   The weights of new dependencies are recalculated by distributing the
			   weight of the dependency of the closed stream proportionally based on
			   the weights of its dependencies. */
			int total = 0;
			for (Iterator<RedBlackTreeInteger.Node<List<DependencyNode>>> itChildren = parent.dependentStreams.nodeIterator();
				itChildren.hasNext(); ) {
				RedBlackTreeInteger.Node<List<DependencyNode>> children = itChildren.next();
				total += children.getValue() * children.getElement().size();
			}
			if (total <= 0) total = 1;
			for (Iterator<RedBlackTreeInteger.Node<List<DependencyNode>>> itChildren = parent.dependentStreams.nodeIterator();
				itChildren.hasNext(); ) {
				RedBlackTreeInteger.Node<List<DependencyNode>> children = itChildren.next();
				int val = parentWeight * children.getValue() / total;
				List<DependencyNode> list = dependentStreams.get(val);
				if (list == null) {
					list = new LinkedList<>();
					dependentStreams.add(val, list);
				}
				list.addAll(children.getElement());
			}
		}
		
		private void adjustSendWindowSize(long diff) {
			sendWindowSize += diff;
			for (List<DependencyNode> children : dependentStreams) {
				for (DependencyNode child : children) {
					child.adjustSendWindowSize(diff);
				}
			}
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
			LinkedList<DependencyNode> list = new LinkedList<>();
			list.add(newNode);
			dependency.dependentStreams.add(dependencyWeight, list);
			if (current != null)
				newNode.takeInfoFrom(current);
			dependencyNodes.put(streamId, newNode);
		}
	}
	
	private DependencyNode addStreamNode(DependencyNode parent, int id, int weight) {
		DependencyNode node = new DependencyNode(id,
			remoteSettings != null ? remoteSettings.getWindowSize() : HTTP2Settings.DefaultValues.INITIAL_WINDOW_SIZE,
			localSettings.getWindowSize());
		List<DependencyNode> list = parent.dependentStreams.get(weight);
		if (list != null) {
			list.add(node);
		} else {
			list = new LinkedList<>();
			list.add(node);
			parent.dependentStreams.add(weight, list);
		}
		dependencyNodes.put(id, node);
		return node;
	}

}
