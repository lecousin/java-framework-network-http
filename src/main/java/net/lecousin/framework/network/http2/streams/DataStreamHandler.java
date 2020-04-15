package net.lecousin.framework.network.http2.streams;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.mutable.Mutable;
import net.lecousin.framework.mutable.MutableBoolean;
import net.lecousin.framework.mutable.MutableInteger;
import net.lecousin.framework.network.http.HTTPMessage;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.frame.HTTP2Continuation;
import net.lecousin.framework.network.http2.frame.HTTP2Data;
import net.lecousin.framework.network.http2.frame.HTTP2Frame;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.frame.HTTP2Headers;
import net.lecousin.framework.network.http2.frame.HTTP2Priority;
import net.lecousin.framework.network.http2.frame.HTTP2ResetStream;
import net.lecousin.framework.network.http2.frame.HTTP2WindowUpdate;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.util.Pair;

public class DataStreamHandler extends StreamHandler.Default {
	
	DataStreamHandler(int id) {
		this.id = id;
	}
	
	private int id;
	private StreamState state = StreamState.IDLE;
	private DataHandler dataHandler = null;
	private boolean noBody = false;
	private AsyncConsumer<ByteBuffer, IOException> bodyConsumer;
	
	private enum StreamState {
		/** Idle state: waiting for HEADERS frame. */
		IDLE,
		/** The stream is open, and CONTINUATION frames are expected. */
		OPEN_HEADERS,
		/** The stream is open, headers fully received and DATA frames are expected. */
		OPEN_DATA,
		/** The stream is open, headers and data fully received, and trailers are coming. */
		OPEN_TRAILERS,
	}
	
	@Override
	public int getStreamId() {
		return id;
	}
	
	@Override
	public void closed() {
		if (dataHandler != null)
			dataHandler.close();
		if (bodyConsumer != null) {
			bodyConsumer.error(new ClosedChannelException());
			bodyConsumer = null;
		}
	}

	@Override
	public boolean startFrame(StreamsManager manager, HTTP2FrameHeader header) {
		payloadPos = 0;
		
		switch (header.getType()) {
		case HTTP2FrameHeader.TYPE_SETTINGS:
		case HTTP2FrameHeader.TYPE_PING:
		case HTTP2FrameHeader.TYPE_GOAWAY:
			// MUST be on stream ID 0
			manager.connectionError(HTTP2Error.Codes.PROTOCOL_ERROR, "frame must be on stream 0");
			return false;
			
		case HTTP2FrameHeader.TYPE_PRIORITY:
			// always allowed
			try {
				frame = new HTTP2Priority.Reader(header);
			} catch (HTTP2Error e) {
				manager.connectionError(e.getErrorCode(), "Error reading priority frame: " + e.getMessage());
				return false;
			}
			payloadConsumer = frame.createConsumer();
			return true;
			
		case HTTP2FrameHeader.TYPE_RST_STREAM:
			frame = new HTTP2ResetStream.Reader();
			payloadConsumer = frame.createConsumer();
			return true;
			
		case HTTP2FrameHeader.TYPE_WINDOW_UPDATE:
			frame = new HTTP2WindowUpdate.Reader();
			payloadConsumer = frame.createConsumer();
			return true;
			
		case HTTP2FrameHeader.TYPE_HEADERS:
			if (manager.currentDecompressionStreamId != -1) {
				// Header compression is stateful.  One compression context and one
				// decompression context are used for the entire connection
				// Header blocks MUST be transmitted as a contiguous sequence of frames, with no
				// interleaved frames of any other type or from any other stream.
				manager.connectionError(HTTP2Error.Codes.PROTOCOL_ERROR, "No concurrency is allowed for headers transmission");
				return false;
			}
			if (StreamState.IDLE.equals(state)) {
				state = StreamState.OPEN_HEADERS;
				dataHandler = manager.createDataHandler(id);
			} else if (StreamState.OPEN_DATA.equals(state)) {
				// start trailers
				if (bodyConsumer != null) {
					bodyConsumer.end();
					bodyConsumer = null;
				}
				dataHandler.endOfBody(manager, this);
				state = StreamState.OPEN_TRAILERS;
			} else {
				manager.connectionError(HTTP2Error.Codes.PROTOCOL_ERROR, "Invalid stream state");
				return false;
			}
			if (header.getPayloadLength() == 0) {
				onEndOfPayload(manager, header);
				return true;
			}
			manager.currentDecompressionStreamId = id;
			frame = new HTTP2Headers.Reader(header, manager.decompressionContext,
				dataHandler.getReceivedHeaders(), dataHandler.createPseudoHeaderHandler());
			payloadConsumer = frame.createConsumer();
			return true;
			
		case HTTP2FrameHeader.TYPE_CONTINUATION:
			if (!StreamState.OPEN_HEADERS.equals(state) && !StreamState.OPEN_TRAILERS.equals(state)) {
				manager.connectionError(HTTP2Error.Codes.PROTOCOL_ERROR, "Invalid stream state");
				return false;
			}
			if (manager.currentDecompressionStreamId != id) {
				manager.connectionError(HTTP2Error.Codes.PROTOCOL_ERROR, "Invalid stream id");
				return false;
			}
			if (header.getPayloadLength() == 0) {
				onEndOfPayload(manager, header);
				return true;
			}
			frame = new HTTP2Continuation.Reader(header, manager.decompressionContext,
				dataHandler.getReceivedHeaders(), dataHandler.createPseudoHeaderHandler());
			payloadConsumer = frame.createConsumer();
			return true;
			
		case HTTP2FrameHeader.TYPE_DATA:
			if (!StreamState.OPEN_DATA.equals(state)) {
				resetStream(manager, HTTP2Error.Codes.PROTOCOL_ERROR);
				manager.consumedConnectionRecvWindowSize(header.getPayloadLength());
				return true;
			}
			if (header.getPayloadLength() == 0) {
				onEndOfPayload(manager, header);
				return true;
			}
			if (bodyConsumer == null) {
				manager.getLogger().error("No body consumer for DATA");
				resetStream(manager, HTTP2Error.Codes.INTERNAL_ERROR);
				manager.consumedConnectionRecvWindowSize(header.getPayloadLength());
				return true;
			}
			payloadConsumer = new HTTP2Data.Reader(header, bodyConsumer).createConsumer();
			return true;
			
		default:
			// unknown
			if (header.getPayloadLength() == 0)
				return true;
			payloadConsumer = new HTTP2Frame.Reader.SkipConsumer(header.getPayloadLength());
			return true;
		}
	}

	@Override
	@SuppressWarnings("java:S3776") // complexity
	protected void onEndOfPayload(StreamsManager manager, HTTP2FrameHeader header) {
		if (frame instanceof HTTP2Priority) {
			HTTP2Priority prio = (HTTP2Priority)frame;
			if (prio.hasPriority()) {
				try {
					manager.addDependency(id, prio.getStreamDependency(),
						prio.getDependencyWeight(), prio.isExclusiveDependency());
				} catch (HTTP2Error e) {
					resetStream(manager, e.getErrorCode());
					return;
				}
			}
		}
		
		switch (header.getType()) {
		case HTTP2FrameHeader.TYPE_HEADERS:
			if (StreamState.OPEN_TRAILERS.equals(state)) {
				// trailers
				if (!header.hasFlags(HTTP2Headers.FLAG_END_STREAM)) {
					// this is a malformed request/response, even it is not specified as a protocol
					// error, we don't want to accept such behavior
					manager.connectionError(HTTP2Error.Codes.PROTOCOL_ERROR, "Trailer frame must have the END_STREAM flag");
					return;
				}
				if (header.hasFlags(HTTP2Headers.FLAG_END_HEADERS)) {
					// end of trailers
					dataHandler.endOfTrailers(manager, this);
					manager.closeStream(this);
					// if this is a response (client mode), this is the end
					if (manager.isClientMode())
						manager.endOfStream(id);
				}
			} else {
				if (header.hasFlags(HTTP2Headers.FLAG_END_HEADERS)) {
					// release decompression context
					manager.currentDecompressionStreamId = -1;
					if (header.hasFlags(HTTP2Headers.FLAG_END_STREAM)) {
						// end of headers and no body
						dataHandler.emptyEntityReceived(manager, this);
						noBody = true;
						manager.closeStream(this);
						// if this is a response (client mode), this is the end
						if (manager.isClientMode())
							manager.endOfStream(id);
					} else {
						// end of headers, start data
						state = StreamState.OPEN_DATA;
					}
					try {
						bodyConsumer = dataHandler.endOfHeaders(manager, this);
					} catch (Exception e) {
						manager.getLogger().error("Error processing headers", e);
						resetStream(manager, HTTP2Error.Codes.INTERNAL_ERROR);
					}
					break;
				}
				if (header.hasFlags(HTTP2Headers.FLAG_END_STREAM)) {
					// expect continuation but no data
					dataHandler.emptyEntityReceived(manager, this);
					noBody = true;
				}
				// continuation expected
				state = StreamState.OPEN_HEADERS;
			}
			break;
			
		case HTTP2FrameHeader.TYPE_CONTINUATION:
			if (header.hasFlags(HTTP2Continuation.FLAG_END_HEADERS)) {
				// release decompression context
				manager.currentDecompressionStreamId = -1;
				if (StreamState.OPEN_TRAILERS.equals(state)) {
					// end of trailers
					dataHandler.endOfTrailers(manager, this);
					manager.closeStream(this);
					// if this is a response (client mode), this is the end
					if (manager.isClientMode())
						manager.endOfStream(id);
				} else {
					// update state
					state = StreamState.OPEN_DATA;
					try {
						bodyConsumer = dataHandler.endOfHeaders(manager, this);
					} catch (Exception e) {
						manager.getLogger().error("Error processing headers", e);
						resetStream(manager, HTTP2Error.Codes.INTERNAL_ERROR);
						break;
					}
					if (noBody) {
						manager.closeStream(this);
						// if this is a response (client mode), this is the end
						if (manager.isClientMode())
							manager.endOfStream(id);
					}
				}
			}
			break;
			
		case HTTP2FrameHeader.TYPE_DATA:
			manager.consumedConnectionRecvWindowSize(header.getPayloadLength());
			if (header.hasFlags(HTTP2Data.FLAG_END_STREAM)) {
				// end of body
				bodyConsumer.end();
				bodyConsumer = null;
				dataHandler.endOfBody(manager, this);
				// no trailer
				dataHandler.endOfTrailers(manager, this);
				manager.closeStream(this);
				// if this is a response (client mode), this is the end
				if (manager.isClientMode())
					manager.endOfStream(id);
			} else {
				// body or trailers can follow
				// send a WINDOW_UPDATE as we consumed the data
				manager.sendFrame(new HTTP2WindowUpdate.Writer(id, header.getPayloadLength()), false);
			}
			break;
			
		case HTTP2FrameHeader.TYPE_RST_STREAM:
			int error = ((HTTP2ResetStream)frame).getErrorCode();
			if (error != HTTP2Error.Codes.NO_ERROR)
				manager.getLogger().error("Error " + error + " returned by remote on stream " + id);
			manager.closeStream(this);
			manager.endOfStream(id);
			break;
			
		case HTTP2FrameHeader.TYPE_WINDOW_UPDATE:
			manager.incrementStreamSendWindowSize(id, ((HTTP2WindowUpdate)frame).getIncrement());
			break;
			
		default:
			break;
		}
	}
	
	@Override
	protected void error(HTTP2Error error, StreamsManager manager, Async<IOException> onConsumed) {
		manager.getLogger().error(error.getMessage());
		resetStream(manager, error.getErrorCode());
		onConsumed.error(IO.error(error));
	}
	
	public void resetStream(StreamsManager manager, int errorCode) {
		if (errorCode != HTTP2Error.Codes.NO_ERROR || !StreamState.IDLE.equals(state)) {
			if (manager.getLogger().debug()) manager.getLogger().debug("Reset stream " + id + " with code " + errorCode);
			HTTP2ResetStream.Writer frame = new HTTP2ResetStream.Writer(id, errorCode);
			manager.sendFrame(frame, false);
			manager.closeStream(this);
		}
		if (bodyConsumer != null) {
			bodyConsumer.error(new IOException("HTTP/2 stream closed"));
			bodyConsumer = null;
		}
	}
	
	public static void sendBody(
		StreamsManager manager, int streamId,
		HTTPMessage<?> message, Async<IOException> sent,
		Long bodySize, AsyncProducer<ByteBuffer, IOException> body,
		int maxBodySizeToProduce
	) {
		if (bodySize != null && bodySize.longValue() == 0) {
			sendTrailers(manager, streamId, message, sent);
			return;
		}
		produceBody(manager, streamId, message, sent, body,
			new Mutable<>(null), new MutableInteger(0), maxBodySizeToProduce, new MutableBoolean(false));
	}
	
	@SuppressWarnings("java:S107") // number of parameters
	private static void produceBody(
		StreamsManager manager, int streamId,
		HTTPMessage<?> message, Async<IOException> sent,
		AsyncProducer<ByteBuffer, IOException> body, Mutable<HTTP2Data.Writer> lastWriter,
		MutableInteger sizeProduced, int maxBodySizeToProduce, MutableBoolean productionPaused
	) {
		Priority prio = Task.getCurrentPriority();
		body.produce("Produce HTTP/2 body data frame", prio)
		.thenStart("Handle produced data frame", prio, data -> {
			if (data == null) {
				// end of data
				if (message.getTrailerHeadersSuppliers() == null) {
					// end of stream
					if (lastWriter.get() != null && lastWriter.get().setEndOfStream()) {
						sent.unblock();
						return;
					}
					HTTP2Data.Writer w = new HTTP2Data.Writer(streamId, null, true,
						frameSent -> sent.unblock());
					manager.sendFrame(w, false).onError(sent::error);
					return;
				}
				// trailers will come
				sendTrailers(manager, streamId, message, sent);
				return;
			}
			boolean delayNextProduction;
			synchronized (sizeProduced) {
				sizeProduced.add(data.remaining());
				delayNextProduction = sizeProduced.get() >= maxBodySizeToProduce;
				if (delayNextProduction) productionPaused.set(true);
			}
			if (lastWriter.get() == null || !lastWriter.get().addData(data)) {
				lastWriter.set(new HTTP2Data.Writer(streamId, data, false, consumed -> {
					boolean needToProduce;
					synchronized (sizeProduced) {
						sizeProduced.sub(consumed.getValue2().intValue());
						needToProduce = productionPaused.get() && sizeProduced.get() < maxBodySizeToProduce;
						if (needToProduce) productionPaused.set(false);
					}
					if (needToProduce)
						produceBody(manager, streamId, message, sent, body, lastWriter,
							sizeProduced, maxBodySizeToProduce, productionPaused);
				}));
				manager.sendFrame(lastWriter.get(), false).onError(sent::error);
			}
			if (!delayNextProduction)
				produceBody(manager, streamId, message, sent, body, lastWriter, sizeProduced, maxBodySizeToProduce, productionPaused);
		}, sent);
	}
	
	@SuppressWarnings("java:S1602") // more readable
	private static void sendTrailers(StreamsManager manager, int streamId, HTTPMessage<?> message, Async<IOException> sent) {
		if (message.getTrailerHeadersSuppliers() == null) {
			sent.unblock();
			return;
		}
		List<MimeHeader> headers = message.getTrailerHeadersSuppliers().get();
		if (headers.isEmpty()) {
			// finally not ! we need to send end of stream
			HTTP2Data.Writer w = new HTTP2Data.Writer(streamId, null, true,
				frameSent -> sent.unblock());
			manager.sendFrame(w, false).onError(sent::error);
			return;
		}
		List<Pair<String, String>> list = new LinkedList<>();
		for (MimeHeader h : headers)
			list.add(new Pair<>(h.getNameLowerCase(), h.getRawValue()));
		manager.reserveCompressionContext(streamId).thenStart("Send HTTP/2 trailers", Priority.NORMAL, compressionContext -> {
			manager.sendFrame(new HTTP2Headers.Writer(streamId, list, true, compressionContext, () -> {
				manager.releaseCompressionContext(streamId);
				sent.unblock();
			}), false).onError(sent::error);
		}, sent);
	}
	
}
