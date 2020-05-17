package net.lecousin.framework.network.http2.connection;

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
import net.lecousin.framework.mutable.Mutable;
import net.lecousin.framework.mutable.MutableBoolean;
import net.lecousin.framework.mutable.MutableInteger;
import net.lecousin.framework.network.http.HTTPMessage;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.HTTP2PseudoHeaderHandler;
import net.lecousin.framework.network.http2.frame.HTTP2Continuation;
import net.lecousin.framework.network.http2.frame.HTTP2Data;
import net.lecousin.framework.network.http2.frame.HTTP2Frame;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.frame.HTTP2Headers;
import net.lecousin.framework.network.http2.frame.HTTP2Priority;
import net.lecousin.framework.network.http2.frame.HTTP2ResetStream;
import net.lecousin.framework.network.http2.frame.HTTP2UnknownFrame;
import net.lecousin.framework.network.http2.frame.HTTP2WindowUpdate;
import net.lecousin.framework.network.http2.hpack.HPackCompress;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.util.Pair;

public abstract class HTTP2Stream {

	protected enum State {
		WAITING_HEADERS, // client mode while sending request, or server mode if first frame was PRIORITY
		HEADERS, // wait continuation
		DATA, // wait data
		TRAILERS, // wait continuation
		CLOSE // server mode only while sending response
	}
	
	protected HTTP2Connection conn;
	private int id;
	State state;
	private boolean noData = false;
	private AsyncConsumer<ByteBuffer, IOException> bodyConsumer;
	
	public HTTP2Stream(HTTP2Connection conn, int id) {
		this.conn = conn;
		this.id = id;
	}
	
	public final int getStreamId() {
		return id;
	}
	
	/** Called when opening a local stream. */
	void setId(int id) {
		this.id = id;
		state = State.WAITING_HEADERS;
	}
	
	protected void closed() {
		if (bodyConsumer != null) {
			bodyConsumer.error(new ClosedChannelException());
			bodyConsumer = null;
		}
	}
	
	AsyncConsumer<ByteBuffer, IOException> getBodyConsumer() {
		return bodyConsumer;
	}

	protected abstract HTTP2PseudoHeaderHandler createPseudoHeaderHandler();
	
	protected abstract MimeHeaders getReceivedHeaders();
	
	protected abstract void endOfHeadersWithoutBody();
	
	protected abstract AsyncConsumer<ByteBuffer, IOException> endOfHeadersWithBody() throws Exception;
	
	protected abstract void endOfBody();
	
	protected abstract void endOfTrailers();
	
	protected abstract void errorReceived(int errorCode);
	
	void processFrame(HTTP2FrameHeader header, HTTP2Frame.Reader frame) {
		if (frame instanceof HTTP2Priority) {
			HTTP2Priority prio = (HTTP2Priority)frame;
			if (prio.hasPriority())
				processPriority(prio);
		}

		if (frame instanceof HTTP2UnknownFrame)
			return;
		
		switch (header.getType()) {
		case HTTP2FrameHeader.TYPE_HEADERS:
			if (header.hasFlags(HTTP2Headers.FLAG_END_HEADERS))
				conn.releaseDecompression(header.getStreamId());
			processHeaders(header);
			return;
			
		case HTTP2FrameHeader.TYPE_CONTINUATION:
			if (header.hasFlags(HTTP2Headers.FLAG_END_HEADERS))
				conn.releaseDecompression(header.getStreamId());
			processContinuation(header);
			return;
			
		case HTTP2FrameHeader.TYPE_DATA:
			conn.decrementConnectionRecvWindowSize(header.getPayloadLength());
			processData(header);
			return;
			
		case HTTP2FrameHeader.TYPE_RST_STREAM:
			processResetStream((HTTP2ResetStream.Reader)frame);
			return;
			
		case HTTP2FrameHeader.TYPE_WINDOW_UPDATE:
			processWindowUpdate((HTTP2WindowUpdate.Reader)frame);
			return;
			
		default:
			break;
		}
	}
	
	protected void processPriority(HTTP2Priority prio) {
		conn.addDependency(id, prio.getStreamDependency(), prio.getDependencyWeight(), prio.isExclusiveDependency());
	}
	
	protected void processHeaders(HTTP2FrameHeader header) {
		if (State.DATA.equals(state)) {
			// start trailers
			// TODO we can cancel sending any pending window update as we won't receive data anymore on this stream
			if (bodyConsumer != null) {
				bodyConsumer.end();
				bodyConsumer = null;
			}
			endOfBody();
			state = State.TRAILERS;
			// trailers
			if (!header.hasFlags(HTTP2Headers.FLAG_END_STREAM)) {
				// this is a malformed request/response, even it is not specified as a protocol
				// error, we don't want to accept such behavior
				conn.error(new HTTP2Error(0, HTTP2Error.Codes.PROTOCOL_ERROR, "Trailer frame must have the END_STREAM flag"), null);
				return;
			}
			processEndOfTrailers();
			return;
		}
		noData = header.hasFlags(HTTP2Headers.FLAG_END_STREAM);
		if (header.hasFlags(HTTP2Headers.FLAG_END_HEADERS)) {
			// no continuation
			processEndOfHeaders();
			return;
		}
		state = State.HEADERS;
	}
	
	protected void processContinuation(HTTP2FrameHeader header) {
		if (!header.hasFlags(HTTP2Continuation.FLAG_END_HEADERS))
			return;
		if (State.TRAILERS.equals(state))
			processEndOfTrailers();
		else
			processEndOfHeaders();
	}
	
	private void processEndOfHeaders() {
		try {
			if (noData)
				endOfHeadersWithoutBody();
			else
				bodyConsumer = endOfHeadersWithBody();
		} catch (Exception e) {
			conn.logger.error("Error processing headers", e);
			conn.error(new HTTP2Error(id, HTTP2Error.Codes.INTERNAL_ERROR), null);
			return;
		}
		if (noData) {
			// if this is a response (client mode), this is the end
			if (conn.isClientMode())
				conn.endOfStream(this);
		} else {
			// end of headers, start data
			state = State.DATA;
		}
	}
	
	private void processEndOfTrailers() {
		state = State.CLOSE;
		endOfTrailers();
		// if this is a response (client mode), this is the end
		if (conn.isClientMode())
			conn.endOfStream(this);
	}
	
	protected void processData(HTTP2FrameHeader header) {
		if (header.hasFlags(HTTP2Data.FLAG_END_STREAM)) {
			// TODO we can cancel sending any pending window update as we won't receive data anymore on this stream (as soon as the header was received)
			// end of body
			if (bodyConsumer != null)
				bodyConsumer.end();
			bodyConsumer = null;
			endOfBody();
			// no trailer
			processEndOfTrailers();
		} else {
			// body or trailers can follow
			// send a WINDOW_UPDATE as we consumed the data
			conn.sendFrame(new HTTP2WindowUpdate.Writer(id, header.getPayloadLength()), false, false);
		}
	}
	
	protected void processResetStream(HTTP2ResetStream.Reader frame) {
		int error = frame.getErrorCode();
		if (error != HTTP2Error.Codes.NO_ERROR) {
			conn.logger.error("Error " + error + " returned by remote on stream " + id);
			errorReceived(error);
		}
		conn.endOfStream(this);
	}
	
	protected void processWindowUpdate(HTTP2WindowUpdate.Reader frame) {
		conn.incrementStreamSendWindowSize(id, frame.getIncrement());
	}
	
	protected void send(
		List<Pair<String, String>> headers,
		HTTPMessage<?> message, Async<IOException> sent,
		Long bodySize, AsyncProducer<ByteBuffer, IOException> body,
		int maxBodySizeToProduce
	) {
		if (id <= 0)
			conn.reserveCompressionContextAndOpenStream(this).thenStart("Send HTTP/2 headers", Priority.NORMAL, reservation -> {
				id = reservation.getValue2().intValue();
				sendHeaders(headers, reservation.getValue1(), message, sent, bodySize, body, maxBodySizeToProduce);
			}, sent);
		else
			conn.reserveCompressionContext(this).thenStart("Send HTTP/2 headers", Priority.NORMAL, compressionContext -> {
				message.setAttribute(HTTPRequestContext.REQUEST_ATTRIBUTE_NANOTIME_RESPONSE_SEND_START,
					Long.valueOf(System.nanoTime()));
				sendHeaders(headers, compressionContext, message, sent, bodySize, body, maxBodySizeToProduce);
			}, sent);
	}
	
	private void sendHeaders(
		List<Pair<String, String>> headers, HPackCompress compressionContext,
		HTTPMessage<?> message, Async<IOException> sent,
		Long bodySize, AsyncProducer<ByteBuffer, IOException> body,
		int maxBodySizeToProduce
	) {
		boolean eos = message.getTrailerHeadersSuppliers() == null && bodySize != null && bodySize.longValue() == 0;
		conn.sendFrame(new HTTP2Headers.Writer(id, headers, eos, compressionContext, () -> {
			conn.releaseCompressionContext(id);
			if (eos)
				sent.unblock();
			else
				sendBody(message, sent, bodySize, body, maxBodySizeToProduce);
		}), !conn.isClientMode() && eos, false).onError(sent::error);
	}
	
	private void sendBody(
		HTTPMessage<?> message, Async<IOException> sent,
		Long bodySize, AsyncProducer<ByteBuffer, IOException> body,
		int maxBodySizeToProduce
	) {
		if (bodySize != null && bodySize.longValue() == 0) {
			sendTrailers(message, sent);
			return;
		}
		produceBody(message, sent, body,
			new Mutable<>(null), new MutableInteger(0), maxBodySizeToProduce, new MutableBoolean(false));
	}
		
	private void produceBody(
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
					if (lastWriter.get() != null) {
						if (conn.isClientMode()) {
							if (lastWriter.get().setEndOfStream()) {
								sent.unblock();
								return;
							}
						} else {
							StreamNode node = conn.lockProduction(id);
							if (lastWriter.get().setEndOfStream()) {
								node.closeAfterLastProduction();
								conn.unlockProduction();
								sent.unblock();
								return;
							}
							conn.unlockProduction();
						}
					}
					HTTP2Data.Writer w = new HTTP2Data.Writer(id, null, true,
						frameSent -> sent.unblock());
					conn.sendFrame(w, !conn.isClientMode(), false).onError(sent::error);
					return;
				}
				// trailers will come
				sendTrailers(message, sent);
				return;
			}
			boolean delayNextProduction;
			synchronized (sizeProduced) {
				sizeProduced.add(data.remaining());
				delayNextProduction = sizeProduced.get() >= maxBodySizeToProduce;
				if (delayNextProduction) productionPaused.set(true);
			}
			if (lastWriter.get() == null || !lastWriter.get().addData(data)) {
				lastWriter.set(new HTTP2Data.Writer(id, data, false, consumed -> {
					boolean needToProduce;
					synchronized (sizeProduced) {
						sizeProduced.sub(consumed.getValue2().intValue());
						needToProduce = productionPaused.get() && sizeProduced.get() < maxBodySizeToProduce;
						if (needToProduce) productionPaused.set(false);
					}
					if (needToProduce)
						produceBody(message, sent, body, lastWriter,
							sizeProduced, maxBodySizeToProduce, productionPaused);
				}));
				conn.sendFrame(lastWriter.get(), false, false).onError(sent::error);
			}
			if (!delayNextProduction)
				produceBody(message, sent, body, lastWriter, sizeProduced, maxBodySizeToProduce, productionPaused);
		}, sent);
	}
	
	@SuppressWarnings("java:S1602") // more readable
	private void sendTrailers(HTTPMessage<?> message, Async<IOException> sent) {
		List<MimeHeader> headers = message.getTrailerHeadersSuppliers().get();
		if (headers.isEmpty()) {
			// finally not ! we need to send end of stream
			HTTP2Data.Writer w = new HTTP2Data.Writer(id, null, true, frameSent -> sent.unblock());
			conn.sendFrame(w, !conn.isClientMode(), false).onError(sent::error);
			return;
		}
		List<Pair<String, String>> list = new LinkedList<>();
		for (MimeHeader h : headers)
			list.add(new Pair<>(h.getNameLowerCase(), h.getRawValue()));
		conn.reserveCompressionContext(this).thenStart("Send HTTP/2 trailers", Priority.NORMAL, compressionContext -> {
			conn.sendFrame(new HTTP2Headers.Writer(id, list, true, compressionContext, () -> {
				conn.releaseCompressionContext(id);
				sent.unblock();
			}), !conn.isClientMode(), false).onError(sent::error);
		}, sent);
	}

}
