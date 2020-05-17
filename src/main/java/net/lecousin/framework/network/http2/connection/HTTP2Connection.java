package net.lecousin.framework.network.http2.connection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

import net.lecousin.framework.collections.map.IntegerMap;
import net.lecousin.framework.collections.map.IntegerMapRBT;
import net.lecousin.framework.collections.sort.RedBlackTreeInteger;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.async.ReadWriteLockPoint;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.TCPRemote;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.frame.HTTP2Frame;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.frame.HTTP2GoAway;
import net.lecousin.framework.network.http2.frame.HTTP2ResetStream;
import net.lecousin.framework.network.http2.frame.HTTP2Settings;
import net.lecousin.framework.network.http2.frame.HTTP2WindowUpdate;
import net.lecousin.framework.network.http2.hpack.HPackCompress;
import net.lecousin.framework.network.http2.hpack.HPackDecompress;
import net.lecousin.framework.util.Pair;

public abstract class HTTP2Connection {

	protected TCPRemote remote;
	private boolean clientMode;
	private HTTP2Settings localSettings;
	private HTTP2Settings remoteSettings;
	private Async<IOException> connectionReady = new Async<>();
	protected Logger logger;
	protected ByteArrayCache bufferCache;
	protected boolean closing = false;
	
	private HPackDecompress decompressionContext;
	private HPackCompress compressionContext;
	private int currentDecompressionStreamId = -1;
	private int currentCompressionStreamId = -1;
	private LinkedList<Pair<HTTP2Stream, AsyncSupplier<Pair<HPackCompress, Integer>, IOException>>>
		compressionContextRequests = new LinkedList<>();

	private int lastRemoteStreamId = 0;
	private int lastLocalStreamId = -1;
	
	private FrameReceiver receiver;
	private FrameSender sender;
	Pinger pinger;
	
	private final StreamNode rootNode;
	private IntegerMap<StreamNode> nodes = new IntegerMapRBT<StreamNode>(10) {
		@Override
		protected int hash(int key) {
			return (key >> 1) % 10;
		}
	};
	private ReadWriteLockPoint nodesLock = new ReadWriteLockPoint();

	protected HTTP2Connection(
		TCPRemote remote, boolean clientMode,
		HTTP2Settings localSettings, boolean localSettingsSent, HTTP2Settings initialRemoteSettings,
		int sendTimeout, int streamIdleTimeout,
		Logger logger, ByteArrayCache bufferCache
	) {
		this.remote = remote;
		this.clientMode = clientMode;
		this.logger = logger;
		this.bufferCache = bufferCache;
		this.localSettings = localSettings;
		this.remoteSettings = initialRemoteSettings;
		compressionContext = new HPackCompress((int)localSettings.getMaxHeaderListSize());

		receiver = new FrameReceiver(this);
		sender = new FrameSender(this, sendTimeout, 128 * 1024); // TODO configurable
		rootNode = new StreamNode(null, HTTP2Settings.DefaultValues.INITIAL_WINDOW_SIZE, localSettings.getWindowSize());
		nodes.put(0, rootNode);
		pinger = new Pinger(this);
		
		if (remoteSettings != null) {
			decompressionContext = new HPackDecompress((int)remoteSettings.getHeaderTableSize());
			rootNode.sendWindowSize = remoteSettings.getWindowSize();
		}
		
		// send our settings
		if (!localSettingsSent)
			sendFrame(new HTTP2Settings.Writer(localSettings, true), false, false);
		
		if (remoteSettings != null) {
			// send ACK
			sendFrame(new HTTP2Frame.EmptyWriter(
				new HTTP2FrameHeader(0, HTTP2FrameHeader.TYPE_SETTINGS, HTTP2Settings.FLAG_ACK, 0)), false, false);
			connectionReady.unblock();
		}

		remote.onclosed(() -> Task.cpu("Closing HTTP/2 streams", Priority.NORMAL, t -> {
			close();
			return null;
		}).start());

	}
	
	public void close() {
		closing = true;
		if (logger.debug())
			logger.debug("Remote closed - clean HTTP/2 connection");
		ClosedChannelException closed = new ClosedChannelException();
		synchronized (compressionContextRequests) {
			while (!compressionContextRequests.isEmpty())
				compressionContextRequests.removeFirst().getValue2().error(closed);
		}
		sender.close(closed);
		nodesLock.startWrite();
		rootNode.forceClose(closed);
		ArrayList<StreamNode> list = new ArrayList<>(nodes.size());
		for (Iterator<StreamNode> it = nodes.values(); it.hasNext(); )
			list.add(it.next());
		nodes.clear();
		nodesLock.endWrite();
		for (StreamNode node : list)
			if (node.stream != null)
				node.stream.closed();
	}
	
	// --- General info ---
	
	public TCPRemote getRemote() {
		return remote;
	}
	
	public boolean isClientMode() {
		return clientMode;
	}

	public Async<IOException> getConnectionReady() {
		return connectionReady;
	}
	
	public boolean isClosing() {
		return closing;
	}
	
	public Logger getLogger() {
		return logger;
	}
	
	/** For test purpose only, else the settings MUST NOT be modified. */
	@Deprecated
	public HTTP2Settings getRemoteSettings() {
		return remoteSettings;
	}
	
	// --- Settings ---
	
	void applyRemoteSettings(HTTP2Settings settings) {
		if (remoteSettings == null) {
			remoteSettings = settings;
			decompressionContext = new HPackDecompress((int)settings.getHeaderTableSize());
			// send ACK
			sendFrame(new HTTP2Frame.EmptyWriter(
				new HTTP2FrameHeader(0, HTTP2FrameHeader.TYPE_SETTINGS, HTTP2Settings.FLAG_ACK, 0)), false, false);
			connectionReady.unblock();
		} else {
			long previousWindowSize = remoteSettings.getWindowSize();
			remoteSettings.set(settings);
			decompressionContext.updateMaximumDynamicTableSize((int)settings.getHeaderTableSize());
			if (remoteSettings.getWindowSize() != previousWindowSize) {
				long diff = remoteSettings.getWindowSize() - previousWindowSize;
				nodesLock.startRead();
				boolean launchProduction = rootNode.adjustSendWindowSize(diff);
				nodesLock.endRead();
				if (launchProduction)
					sender.launchFrameProduction();
			}
		}
	}
	
	int getSendMaxFrameSize() {
		return (int)(remoteSettings != null ? remoteSettings.getMaxFrameSize() : HTTP2Settings.DefaultValues.MAX_FRAME_SIZE);
	}

	// --- Receive data ---
	
	/** Consume data. */
	public void consumeDataFromRemote(ByteBuffer data) {
		if (!closing)
			receiver.consume(data);
	}
	
	protected abstract void acceptNewDataFromRemote();
	
	long getMaximumReceivedPayloadLength() {
		return localSettings.getMaxFrameSize();
	}
	
	// --- Decompression context ---
	
	/** Called when receiving a HEADERS frame. */
	HPackDecompress takeDecompressor(int streamId) throws HTTP2Error {
		// Header compression is stateful.  One compression context and one
		// decompression context are used for the entire connection
		// Header blocks MUST be transmitted as a contiguous sequence of frames, with no
		// interleaved frames of any other type or from any other stream.
		if (currentDecompressionStreamId != -1)
			throw new HTTP2Error(0, HTTP2Error.Codes.PROTOCOL_ERROR,
				"No concurrency is allowed for headers, currently decompressing on stream "	+ currentDecompressionStreamId
				+ " while receiving new headers on stream " + streamId);
		currentDecompressionStreamId = streamId;
		return decompressionContext;
	}
	
	/** Called when receiving a CONTINUATION frame. */
	HPackDecompress reuseDecompressor(int streamId) throws HTTP2Error {
		if (currentDecompressionStreamId != streamId)
			throw new HTTP2Error(0, HTTP2Error.Codes.PROTOCOL_ERROR, "Invalid decompression context");
		return decompressionContext;
	}
	
	void releaseDecompression(int streamId) {
		if (currentDecompressionStreamId == streamId)
			currentDecompressionStreamId = -1;
	}
	
	// --- Compression context ---
	
	public AsyncSupplier<HPackCompress, IOException> reserveCompressionContext(HTTP2Stream stream) {
		synchronized (compressionContextRequests) {
			if (currentCompressionStreamId == -1) {
				currentCompressionStreamId = stream.getStreamId();
				return new AsyncSupplier<>(compressionContext, null);
			}
			AsyncSupplier<HPackCompress, IOException> result = new AsyncSupplier<>();
			AsyncSupplier<Pair<HPackCompress, Integer>, IOException> request = new AsyncSupplier<>();
			compressionContextRequests.add(new Pair<>(stream, request));
			request.onDone(p -> result.unblockSuccess(p.getValue1()), result);
			return result;
		}
	}

	public AsyncSupplier<Pair<HPackCompress, Integer>, IOException> reserveCompressionContextAndOpenStream(HTTP2Stream stream) {
		synchronized (compressionContextRequests) {
			if (closing)
				return new AsyncSupplier<>(null, new ClosedChannelException());
			if (currentCompressionStreamId == -1)
				currentCompressionStreamId = -2;
			else {
				AsyncSupplier<Pair<HPackCompress, Integer>, IOException> request = new AsyncSupplier<>();
				compressionContextRequests.add(new Pair<>(stream, request));
				return request;
			}
		}
		openLocalStream(stream);
		if (stream.getStreamId() > 0) {
			currentCompressionStreamId = stream.getStreamId();
			return new AsyncSupplier<>(new Pair<>(compressionContext, Integer.valueOf(currentCompressionStreamId)), null);
		}
		AsyncSupplier<Pair<HPackCompress, Integer>, IOException> request = new AsyncSupplier<>();
		synchronized (compressionContextRequests) {
			currentCompressionStreamId = -1;
			compressionContextRequests.add(new Pair<>(stream, request));
		}
		return request;
	}
	
	public void releaseCompressionContext(int streamId) {
		Pair<HTTP2Stream, AsyncSupplier<Pair<HPackCompress, Integer>, IOException>> request;
		synchronized (compressionContextRequests) {
			if (currentCompressionStreamId != streamId)
				return;
			request = compressionContextRequests.poll(); // TODO use priority
			if (request == null) {
				currentCompressionStreamId = -1;
				return;
			}
			if (request.getValue1().getStreamId() <= 0) {
				currentCompressionStreamId = -2;
			} else {
				currentCompressionStreamId = request.getValue1().getStreamId();
			}
		}
		if (request.getValue1().getStreamId() <= 0) {
			openLocalStream(request.getValue1());
			synchronized (compressionContextRequests) {
				int id = request.getValue1().getStreamId();
				if (id <= 0) {
					currentCompressionStreamId = -1;
					compressionContextRequests.addFirst(request);
					return;
				}
				currentCompressionStreamId = id;
			}
		}
		request.getValue2().unblockSuccess(new Pair<>(compressionContext, Integer.valueOf(currentCompressionStreamId)));
	}
	
	// --- Streams management ---
	
	public int getLastLocalStreamId() {
		return lastLocalStreamId;
	}
	
	public int getLastRemoteStreamId() {
		return lastRemoteStreamId;
	}
	
	HTTP2Stream getDataStream(int id) {
		nodesLock.startRead();
		StreamNode node = nodes.get(id);
		nodesLock.endRead();
		return node != null ? node.stream : null;
	}
	
	void endOfStream(HTTP2Stream stream) {
		nodesLock.startWrite();
		removeStreamLocked(stream);
		nodesLock.endWrite();
		streamRemoved(stream);
		Task.cpu("Check pending requests to open stream", Priority.NORMAL, t -> {
			Pair<HTTP2Stream, AsyncSupplier<Pair<HPackCompress, Integer>, IOException>> request = null;
			synchronized (compressionContextRequests) {
				if (currentCompressionStreamId == -1 && !compressionContextRequests.isEmpty()) {
					request = compressionContextRequests.getFirst();
					if (request == null || request.getValue1().getStreamId() > 0)
						return null;
					currentCompressionStreamId = -2;
				} else {
					return null;
				}
			}
			if (currentCompressionStreamId == -2) {
				openLocalStream(request.getValue1());
				if (request.getValue1().getStreamId() <= 0) {
					currentCompressionStreamId = -1;
					return null;
				}
				synchronized (compressionContextRequests) {
					currentCompressionStreamId = request.getValue1().getStreamId();
					compressionContextRequests.removeFirst();
				}
			}
			if (request != null)
				request.getValue2().unblockSuccess(new Pair<>(compressionContext, Integer.valueOf(currentCompressionStreamId)));
			return null;
		}).start();
	}
	
	private void removeStreamLocked(HTTP2Stream stream) {
		nodes.remove(stream.getStreamId());
		rootNode.remove(stream.getStreamId(), true);
	}
	
	private static void streamRemoved(HTTP2Stream stream) {
		stream.closed();
	}

	protected abstract HTTP2Stream createDataStream(int id);
	
	HTTP2Stream openRemoteStream(int id) throws HTTP2Error {
		if (closing)
			throw new HTTP2Error(0, HTTP2Error.Codes.CANCEL);
		lastRemoteStreamId = id;
		HTTP2Stream stream = createDataStream(id);
		nodesLock.startWrite();
		boolean tooManyStreams = localSettings.getMaxConcurrentStreams() > 0 && nodes.size() > localSettings.getMaxConcurrentStreams();
		if (!tooManyStreams)
			createStreamNode(rootNode, stream, 16);
		nodesLock.endWrite();
		if (!tooManyStreams)
			return stream;
		throw new HTTP2Error(0, HTTP2Error.Codes.ENHANCE_YOUR_CALM, "Too many open streams ("
			+ nodes.size() + "/" + localSettings.getMaxConcurrentStreams() + ")");
	}
	
	private StreamNode createStreamNode(StreamNode parent, HTTP2Stream stream, int weight) {
		StreamNode node = parent.createChild(stream, weight,
			remoteSettings != null ? remoteSettings.getWindowSize() : HTTP2Settings.DefaultValues.INITIAL_WINDOW_SIZE,
			localSettings.getWindowSize());
		nodes.put(stream.getStreamId(), node);
		return node;
	}

	private void openLocalStream(HTTP2Stream stream) {
		nodesLock.startWrite();
		// check we did not reach the maximum specified by the remote
		if (remoteSettings.getMaxConcurrentStreams() > 0 && nodes.size() > remoteSettings.getMaxConcurrentStreams()) {
			nodesLock.endWrite();
			return;
		}
		lastLocalStreamId += 2;
		stream.setId(lastLocalStreamId);
		createStreamNode(rootNode, stream, 16);
		nodesLock.endWrite();
	}
	
	// --- Errors ---
	
	public void resetStream(HTTP2Stream stream, int errorCode) {
		error(new HTTP2Error(stream.getStreamId(), errorCode), null);
	}
	
	void error(HTTP2Error error, ByteBuffer remainingData) {
		if (error.getStreamId() == 0) {
			// connection error
			if (logger.error())
				logger.error("Send connection error " + error.getErrorCode() + ": " + error.getMessage());
			HTTP2GoAway.Writer frame = new HTTP2GoAway.Writer(lastRemoteStreamId, error.getErrorCode(),
				error.getMessage() != null ? error.getMessage().getBytes(StandardCharsets.UTF_8) : null);
			sendFrame(frame, false, true);
			return;
		}
		
		if (logger.error() && error.getErrorCode() != HTTP2Error.Codes.NO_ERROR)
			logger.error("Send stream " + error.getStreamId() + " error " + error.getErrorCode() + ": " + error.getMessage());
		HTTP2ResetStream.Writer frame = new HTTP2ResetStream.Writer(error.getStreamId(), error.getErrorCode());
		sendFrame(frame, true, false);
		if (remainingData != null)
			Task.cpu("Consume HTTP/2 data", t -> {
				consumeDataFromRemote(remainingData);
				return null;
			});
	}
	
	// --- Flow Control ---
	
	/** Called each time a DATA frame is received. */
	void decrementConnectionRecvWindowSize(long size) {
		long increment = 0;
		rootNode.recvWindowSize -= size;
		if (logger.trace()) logger.trace("Connection window recv consumed: " + size
			+ ", remaining = " + rootNode.recvWindowSize);
		if (rootNode.recvWindowSize <= localSettings.getWindowSize() / 2) {
			increment = localSettings.getWindowSize() - rootNode.recvWindowSize;
			rootNode.recvWindowSize += increment;
		}
		if (increment > 0) {
			if (logger.trace())
				logger.trace("Connection window recv increment: " + increment);
			sendFrame(new HTTP2WindowUpdate.Writer(0, increment), false, false);
		}
	}
	
	/** Called each time a WINDOW UPDATE is received on connection stream. */
	void incrementConnectionSendWindowSize(long increment) {
		if (logger.trace())
			logger.trace("Increment connection send window by " + increment + " => " + (rootNode.sendWindowSize + increment));
		boolean wasZero = rootNode.sendWindowSize <= 0;
		rootNode.sendWindowSize += increment;
		if (wasZero && rootNode.sendWindowSize > 0)
			sender.launchFrameProduction();
	}

	void incrementStreamSendWindowSize(int streamId, long increment) {
		nodesLock.startRead();
		StreamNode node = nodes.get(streamId);
		boolean launchProduction = false;
		if (node != null) {
			launchProduction = node.sendWindowSize <= 0 && node.sendWindowSize + increment > 0;
			node.sendWindowSize += increment;
		}
		nodesLock.endRead();
		if (node != null && logger.trace())
			logger.trace("Increment window for stream " + streamId + ": " + increment + ", new size = " + node.sendWindowSize);
		if (launchProduction)
			sender.launchFrameProduction();
	}
	
	void decrementConnectionAndStreamSendWindowSize(int streamId, int size) {
		nodesLock.startRead();
		StreamNode node = nodes.get(streamId);
		if (node != null)
			node.sendWindowSize -= size;
		rootNode.sendWindowSize -= size;
		nodesLock.endRead();
		if (node != null && logger.trace())
			logger.trace("Decrement window for stream " + streamId + ": " + size + ", new size = " + node.sendWindowSize);
	}
	
	void addDependency(int streamId, int dependencyId, int dependencyWeight, boolean isExclusive) {
		if (dependencyId == streamId)
			return;
		nodesLock.startWrite();
		StreamNode stream = nodes.get(streamId);
		StreamNode dependency = nodes.get(dependencyId);
		if (dependency == null || stream == null) {
			// stream does not exist or is closed
			return;
		}
		if (!isExclusive) {
			StreamNode node = rootNode.remove(streamId, false);
			dependency.addChild(node, dependencyWeight);
			return;
		}
		StreamNode node = rootNode.remove(streamId, false);
		for (Iterator<RedBlackTreeInteger.Node<List<StreamNode>>> it = dependency.dependentStreams.nodeIterator(); it.hasNext(); ) {
			RedBlackTreeInteger.Node<List<StreamNode>> n = it.next();
			for (StreamNode child : n.getElement())
				node.addChild(child, n.getValue());
		}
		dependency.dependentStreams = new RedBlackTreeInteger<>();
		LinkedList<StreamNode> list = new LinkedList<>();
		list.add(node);
		dependency.dependentStreams.add(dependencyWeight, list);
		nodesLock.endWrite();
	}


	// --- Send ---
	
	public IAsync<IOException> sendFrame(HTTP2Frame.Writer frame, boolean closeStreamAfter, boolean closeConnectionAfter) {
		if (logger.trace()) logger.trace("Frame to send on stream " + frame.getStreamId()
			+ ": " + HTTP2FrameHeader.getTypeName(frame.getType()));
		if (closing)
			return new Async<>(new ClosedChannelException());
		
		// if last frame, this is a connection error, we can send it as soon as possible
		if (closeConnectionAfter) {
			closing = true;
			LinkedList<ByteBuffer> list = new LinkedList<>();
			while (frame.canProduceMore()) {
				ByteArray data = frame.produce((int)remoteSettings.getMaxFrameSize(), bufferCache);
				list.add(data.toByteBuffer());
			}
			IAsync<IOException> send = remote.send(list, sender.sendTimeout);
			send.onDone(remote::close);
			return send;
		}
		
		// add the frame to the waiting list
		Async<IOException> sp = new Async<>();
		nodesLock.startRead();
		if (closing) {
			nodesLock.endRead();
			sp.error(new ClosedChannelException());
			return sp;
		}
		StreamNode node = nodes.get(frame.getStreamId());
		if (node == null) {
			nodesLock.endRead();
			sp.error(new IOException("Cannot send frame " + frame + " because stream is closed"));
			return sp;
		}
		node.addProducer(frame, sp, closeStreamAfter);
		nodesLock.endRead();
		sender.launchFrameProduction();
		return sp;
	}

	LinkedList<Pair<HTTP2Frame.Writer, Async<IOException>>> peekNextFramesToProduce() {
		List<HTTP2Stream> toClose = new LinkedList<>();
		nodesLock.startWrite();
		LinkedList<Pair<HTTP2Frame.Writer, Async<IOException>>> list =
			rootNode.removeNextFramesToProduce(rootNode.sendWindowSize > 0, toClose);
		for (HTTP2Stream s : toClose)
			removeStreamLocked(s);
		nodesLock.endWrite();
		for (HTTP2Stream s : toClose)
			streamRemoved(s);
		return list;
	}
	
	StreamNode lockProduction(int streamId) {
		nodesLock.startRead();
		return nodes.get(streamId);
	}
	
	void unlockProduction() {
		nodesLock.endRead();
	}
	
	public void ping(byte[] data, Consumer<Boolean> onResponseReceived, long timeout) {
		pinger.sendPing(data, onResponseReceived, timeout);
	}
	
}
