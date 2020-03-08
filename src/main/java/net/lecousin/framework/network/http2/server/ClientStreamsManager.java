package net.lecousin.framework.network.http2.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
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
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.mutable.Mutable;
import net.lecousin.framework.mutable.MutableInteger;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.http1.HTTP1RequestCommandProducer;
import net.lecousin.framework.network.http2.HTTP2Constants;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.frame.HTTP2Data;
import net.lecousin.framework.network.http2.frame.HTTP2Frame;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.frame.HTTP2Headers;
import net.lecousin.framework.network.http2.frame.HTTP2Settings;
import net.lecousin.framework.network.http2.frame.HTTP2WindowUpdate;
import net.lecousin.framework.network.http2.hpack.HPackCompress;
import net.lecousin.framework.network.http2.hpack.HPackDecompress;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.util.Pair;

class ClientStreamsManager {
	
	// TODO may be we shoud limit the data received for each client in case of heavy load ?
	// we could delay the client.waitForData for clients having already things to process ?
	// this may be also relevent for HTTP/1 server...
	
	HTTP2ServerProtocol server;
	TCPServerClient client;
	private HTTP2Settings clientSettings;
	private ConnectionStreamHandler connectionStream;
	private Map<Integer, StreamHandler> clientStreams = new HashMap<>(10);
	private int lastStreamId = 0;
	private boolean closing = false;

	HPackDecompress decompressionContext;
	HPackCompress compressionContext;
	int currentDecompressionStreamId = -1;
	
	private long connectionRecvWindowSize;
	
	ClientStreamsManager(HTTP2ServerProtocol server, TCPServerClient client, HTTP2Settings initialSettings) {
		this.server = server;
		this.client = client;
		clientSettings = initialSettings;
		compressionContext = new HPackCompress(server.getCompressionHeaderTableSize());
		if (clientSettings != null) {
			decompressionContext = new HPackDecompress((int)clientSettings.getHeaderTableSize());
			dependencyTree.windowSize = clientSettings.getWindowSize();
		}
		connectionRecvWindowSize = server.getInitialWindowSize();
	}
	
	void applySettings(HTTP2Settings settings) {
		if (clientSettings == null) {
			clientSettings = settings;
			decompressionContext = new HPackDecompress((int)settings.getHeaderTableSize());
		} else {
			long previousWindowSize = clientSettings.getWindowSize();
			clientSettings.set(settings);
			decompressionContext.updateMaximumDynamicTableSize((int)settings.getHeaderTableSize());
			if (clientSettings.getWindowSize() != previousWindowSize) {
				long diff = clientSettings.getWindowSize() - previousWindowSize;
				synchronized (dependencyTree) {
					dependencyTree.adjustWindowSize(diff);
				}
			}
		}
	}
	
	int getLastStreamId() {
		return lastStreamId;
	}
	
	/** Start processing a frame, return the stream handler or null to stop everything. */
	StreamHandler startFrame(HTTP2FrameHeader header) {
		if (closing)
			return null;
		StreamHandler stream;
		if (header.getStreamId() == 0) {
			stream = connectionStream;
		} else {
			// client stream identifiers MUST be odd
			if ((header.getStreamId() % 2) == 0) {
				HTTP2ServerProtocol.connectionError(client, HTTP2Error.Codes.PROTOCOL_ERROR, null);
				return null;
			}
			stream = clientStreams.get(Integer.valueOf(header.getStreamId() / 2));
			if (stream == null) {
				if (header.getStreamId() <= lastStreamId) {
					// it may be remaining frames sent before we sent a reset stream to the client
					// we need to process it as it may change connection contexts (i.e. decompression context)
					switch (header.getType()) {
					case HTTP2FrameHeader.TYPE_HEADERS:
					case HTTP2FrameHeader.TYPE_CONTINUATION:
						stream = new SkipHeadersFrame(header.getStreamId());
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
						HTTP2ServerProtocol.connectionError(client, HTTP2Error.Codes.PROTOCOL_ERROR, null);
						return null;
					}
				} else {
					// TODO check if maximum is reached
					stream = openStream(header.getStreamId());
				}
			}
		}
		client.setAttribute(HTTP2ServerProtocol.ATTRIBUTE_FRAME_STREAM_HANDLER, stream);
		if (!stream.startFrame(this, header))
			return null;
		return stream;
	}
	
	private ClientStreamHandler openStream(int id) {
		lastStreamId = id;
		ClientStreamHandler stream = new ClientStreamHandler(id);
		clientStreams.put(Integer.valueOf(id / 2), stream);
		synchronized (dependencyTree) {
			addStreamNode(dependencyTree, id, 16);
		}
		return stream;
	}
	
	void closeStream(ClientStreamHandler stream) {
		clientStreams.remove(Integer.valueOf(stream.getStreamId() / 2));
	}
	
	void startProcessing(HTTPRequestContext ctx, ClientStreamHandler stream) {
		if (closing)
			return;
		
		if (ctx.getRequest().getEntity() == null) {
			// body will come
			if (!ctx.getRequest().isExpectingBody()) {
				// TODO error ?
			}
		}
		// TODO manage priority and number of pending requests (both for the client and for the server?)
		if (server.logger.debug())
			server.logger.debug("Processing request from " + ctx.getClient()
				+ ": " + HTTP1RequestCommandProducer.generateString(ctx.getRequest()));
		server.getProcessor().process(ctx);
		
		ctx.getResponse().getReady().onDone(() -> sendHeaders(ctx, stream.getStreamId()));
		ctx.getResponse().getSent().onDone(() -> {
			synchronized (dependencyTree) {
				dependencyTree.remove(stream.getStreamId());
				dependencyNodes.remove(stream.getStreamId() / 2);
			}
		});
	}
	
	private void sendHeaders(HTTPRequestContext ctx, int streamId) {
		Task.cpu("Create HTTP/2 headers frame", (Task<Void, NoException> task) -> {
			// TODO if getReady() has an error, send an error
			// TODO handle range request
			AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> bodyProducer;
			if (ctx.getResponse().getEntity() == null) {
				bodyProducer = new AsyncSupplier<>(new Pair<>(Long.valueOf(0), null), null);
			} else {
				bodyProducer = ctx.getResponse().getEntity().createBodyProducer();
			}
			List<Pair<String, String>> headers = new LinkedList<>();
			headers.add(new Pair<>(HTTP2Constants.Headers.Response.Pseudo.STATUS, Integer.toString(ctx.getResponse().getStatusCode())));
			for (MimeHeader h : ctx.getResponse().getHeaders().getHeaders())
				headers.add(new Pair<>(h.getNameLowerCase(), h.getRawValue()));
			// if available, add the content-length
			boolean isEndOfStream = false;
			if (bodyProducer.isDone() && bodyProducer.getResult().getValue1() != null) {
				if (bodyProducer.getResult().getValue1().longValue() == 0)
					isEndOfStream = true;
				else
					headers.add(new Pair<>("content-length", bodyProducer.getResult().getValue1().toString()));
			}
			if (ctx.getResponse().getTrailerHeadersSuppliers() != null)
				isEndOfStream = false;
			// send headers/continuation frames, then body, then trailers
			sendFrame(new HTTP2Headers.Writer(streamId, headers, isEndOfStream, compressionContext,
				() -> bodyProducer.onDone(
					() -> sendBody(ctx, streamId, bodyProducer.getResult().getValue1(), bodyProducer.getResult().getValue2())
				)
			), false);
			return null;
		}).start();
	}
	
	private void sendBody(HTTPRequestContext ctx, int streamId, Long bodySize, AsyncProducer<ByteBuffer, IOException> body) {
		if (bodySize != null && bodySize.longValue() == 0) {
			sendTrailers(ctx, streamId);
			return;
		}
		produceBody(ctx, streamId, body, new Mutable<>(null), new MutableInteger(0));
	}
	
	private static final int MAX_BODY_SIZE_PRODUCED = 128 * 1024;
	
	private void produceBody(
		HTTPRequestContext ctx, int streamId,
		AsyncProducer<ByteBuffer, IOException> body, Mutable<HTTP2Data.Writer> lastWriter,
		MutableInteger sizeProduced
	) {
		body.produce("Produce HTTP/2 body data frame", Task.getCurrentPriority())
		.onDone(data -> {
			// TODO in a task
			if (data == null) {
				// end of data
				if (ctx.getResponse().getTrailerHeadersSuppliers() == null) {
					// end of stream
					if (lastWriter.get() != null && lastWriter.get().setEndOfStream()) {
						ctx.getResponse().getSent().unblock();
						return;
					}
					HTTP2Data.Writer w = new HTTP2Data.Writer(streamId, null, true,
						sent -> ctx.getResponse().getSent().unblock());
					sendFrame(w, false);
					return;
				}
				// trailers will come
				sendTrailers(ctx, streamId);
				return;
			}
			synchronized (sizeProduced) {
				sizeProduced.add(data.remaining());
			}
			if (lastWriter.get() == null || !lastWriter.get().addData(data)) {
				lastWriter.set(new HTTP2Data.Writer(streamId, data, false, consumed -> {
					boolean canProduce;
					synchronized (sizeProduced) {
						canProduce = sizeProduced.get() >= MAX_BODY_SIZE_PRODUCED &&
							sizeProduced.get() - consumed.getValue2().intValue() < MAX_BODY_SIZE_PRODUCED;
						sizeProduced.sub(consumed.getValue2().intValue());
					}
					if (canProduce)
						produceBody(ctx, streamId, body, lastWriter, sizeProduced);
				}));
				sendFrame(lastWriter.get(), false);
			}
			synchronized (sizeProduced) {
				if (sizeProduced.get() < MAX_BODY_SIZE_PRODUCED)
					produceBody(ctx, streamId, body, lastWriter, sizeProduced);
			}
		}, error -> {
			// TODO
		}, cancel -> {
			// TODO
		});
	}
	
	private void sendTrailers(HTTPRequestContext ctx, int streamId) {
		if (ctx.getResponse().getTrailerHeadersSuppliers() == null) {
			ctx.getResponse().getSent().unblock();
			return;
		}
		List<MimeHeader> headers = ctx.getResponse().getTrailerHeadersSuppliers().get();
		if (headers.isEmpty()) {
			// finally not ! we need to send end of stream
			HTTP2Data.Writer w = new HTTP2Data.Writer(streamId, null, true,
				sent -> ctx.getResponse().getSent().unblock());
			sendFrame(w, false);
			return;
		}
		List<Pair<String, String>> list = new LinkedList<>();
		for (MimeHeader h : headers)
			list.add(new Pair<>(h.getNameLowerCase(), h.getRawValue()));
		sendFrame(new HTTP2Headers.Writer(streamId, list, true, compressionContext, () -> ctx.getResponse().getSent().unblock()), false);
	}
	
	void consumedConnectionRecvWindowSize(long size) {
		connectionRecvWindowSize -= size;
		if (connectionRecvWindowSize <= server.getInitialWindowSize() / 2) {
			long increment = server.getInitialWindowSize() - connectionRecvWindowSize;
			connectionRecvWindowSize += increment;
			sendFrame(new HTTP2WindowUpdate.Writer(0, increment), false);
		}
	}
	
	private Async<IOException> lastSend = new Async<>(true);
	private Async<NoException> frameProduction = new Async<>(true);
	private static final int MAX_FRAMES_PRODUCED = 5;
	
	void sendFrame(HTTP2Frame.Writer frame, boolean closeAfter) {
		synchronized (this) {
			if (closing)
				return;
			
			// if last frame, this is a connection error, we can send it as soon as possible
			if (closeAfter) {
				closing = true;
				LinkedList<ByteBuffer> list = new LinkedList<>();
				while (frame.canProduceMore()) {
					ByteArray data = frame.produce((int)clientSettings.getMaxFrameSize(), server.bufferCache);
					list.add(data.toByteBuffer());
				}
				try {
					client.send(list, server.getSendDataTimeout(), true)
					.onDone(() -> { for (ByteBuffer b : list) server.bufferCache.free(b.array()); });
				} catch (ClosedChannelException e) {
					// do not send
				}
				return;
			}
			
			if (lastSend.isDone() && !lastSend.isSuccessful()) {
				closing = true;
				client.close();
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
						ByteArray data = producer.produce((int)clientSettings.getMaxFrameSize(), server.bufferCache);
						production.add(data.toByteBuffer());
						if (producer instanceof HTTP2Data)
							windowSizeUsed(producer.getStreamId(), data.remaining());
					} while (producer.canProduceMore() && buffersReady.size() + production.size() < MAX_FRAMES_PRODUCED);
					if (buffersReady.size() + production.size() >= MAX_FRAMES_PRODUCED)
						break;
				}

				synchronized (dependencyTree) {
					buffersReady.addAll(production);
				}
				// if nothing currently sent, launch a new send
				synchronized (ClientStreamsManager.this) {
					if (lastSend.isDone()) {
						lastSend = new Async<>();
						List<ByteBuffer> buffers = new LinkedList<>();
						buffersSent = buffers;
						client.newDataToSendWhenPossible(dataProvider, lastSend, server.getSendDataTimeout());
						lastSend.onDone(() -> { for (ByteBuffer b : buffers) server.bufferCache.free(b.array()); });
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
			DependencyNode newNode = new DependencyNode(streamId, clientSettings.getWindowSize());
			newNode.dependentStreams = dependency.dependentStreams;
			dependency.dependentStreams = new RedBlackTreeInteger<>();
			dependency.dependentStreams.add(dependencyWeight, newNode);
			if (current != null)
				newNode.takeInfoFrom(current);
			dependencyNodes.put(streamId / 2, newNode);
		}
	}
	
	private DependencyNode addStreamNode(DependencyNode parent, int id, int weight) {
		DependencyNode node = new DependencyNode(id, clientSettings.getWindowSize());
		parent.dependentStreams.add(weight, node);
		dependencyNodes.put(id / 2, node);
		return node;
	}

}
