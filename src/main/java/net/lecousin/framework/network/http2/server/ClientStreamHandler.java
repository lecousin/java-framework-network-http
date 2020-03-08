package net.lecousin.framework.network.http2.server;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.http.server.HTTPServerResponse;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.HTTP2PseudoHeaderHandler;
import net.lecousin.framework.network.http2.frame.HTTP2Continuation;
import net.lecousin.framework.network.http2.frame.HTTP2Data;
import net.lecousin.framework.network.http2.frame.HTTP2Frame;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.frame.HTTP2Headers;
import net.lecousin.framework.network.http2.frame.HTTP2Priority;
import net.lecousin.framework.network.http2.frame.HTTP2ResetStream;
import net.lecousin.framework.network.http2.frame.HTTP2WindowUpdate;
import net.lecousin.framework.network.mime.entity.EmptyEntity;

class ClientStreamHandler extends StreamHandler.Default {
	
	ClientStreamHandler(int id) {
		this.id = id;
	}
	
	private int id;
	private StreamState state = StreamState.IDLE;
	private HTTPRequestContext ctx;
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
	
	public int getStreamId() {
		return id;
	}
	
	@Override
	public boolean startFrame(ClientStreamsManager clientManager, HTTP2FrameHeader header) {
		payloadPos = 0;
		
		switch (header.getType()) {
		case HTTP2FrameHeader.TYPE_SETTINGS:
		case HTTP2FrameHeader.TYPE_PING:
		case HTTP2FrameHeader.TYPE_GOAWAY:
			// MUST be on stream ID 0
			HTTP2ServerProtocol.connectionError(clientManager.client, HTTP2Error.Codes.PROTOCOL_ERROR, "frame must be on stream 0");
			return false;
			
		case HTTP2FrameHeader.TYPE_PRIORITY:
			// always allowed
			try {
				frame = new HTTP2Priority.Reader(header);
			} catch (HTTP2Error e) {
				HTTP2ServerProtocol.connectionError(clientManager.client, e.getErrorCode(), e.getMessage());
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
			if (clientManager.currentDecompressionStreamId != -1) {
				// Header compression is stateful.  One compression context and one
				// decompression context are used for the entire connection
				// Header blocks MUST be transmitted as a contiguous sequence of frames, with no
				// interleaved frames of any other type or from any other stream.
				HTTP2ServerProtocol.connectionError(clientManager.client, HTTP2Error.Codes.PROTOCOL_ERROR,
					"No concurrency is allowed for headers transmission");
				return false;
			}
			if (StreamState.IDLE.equals(state)) {
				state = StreamState.OPEN_HEADERS;
				ctx = new HTTPRequestContext(clientManager.client, new HTTPRequest(),
					new HTTPServerResponse(), clientManager.server.getErrorHandler());
			} else if (StreamState.OPEN_DATA.equals(state)) {
				// start trailers
				if (bodyConsumer != null) {
					bodyConsumer.end();
					bodyConsumer = null;
				}
				state = StreamState.OPEN_TRAILERS;
			} else {
				HTTP2ServerProtocol.connectionError(clientManager.client, HTTP2Error.Codes.PROTOCOL_ERROR, "Invalid stream state");
				return false;
			}
			if (header.getPayloadLength() == 0) {
				onEndOfPayload(clientManager, header);
				return true;
			}
			clientManager.currentDecompressionStreamId = id;
			frame = new HTTP2Headers.Reader(
				header, clientManager.decompressionContext,
				ctx.getRequest().getHeaders(), new HTTP2PseudoHeaderHandler.Request(ctx.getRequest()));
			payloadConsumer = frame.createConsumer();
			return true;
			
		case HTTP2FrameHeader.TYPE_CONTINUATION:
			if (!StreamState.OPEN_HEADERS.equals(state) && !StreamState.OPEN_TRAILERS.equals(state)) {
				HTTP2ServerProtocol.connectionError(clientManager.client, HTTP2Error.Codes.PROTOCOL_ERROR, "Invalid stream state");
				return false;
			}
			if (clientManager.currentDecompressionStreamId != id) {
				HTTP2ServerProtocol.connectionError(clientManager.client, HTTP2Error.Codes.PROTOCOL_ERROR, "Invalid stream id");
				return false;
			}
			if (header.getPayloadLength() == 0) {
				onEndOfPayload(clientManager, header);
				return true;
			}
			frame = new HTTP2Continuation.Reader(
				header, clientManager.decompressionContext,
				ctx.getRequest().getHeaders(), new HTTP2PseudoHeaderHandler.Request(ctx.getRequest()));
			payloadConsumer = frame.createConsumer();
			return true;
			
		case HTTP2FrameHeader.TYPE_DATA:
			if (!StreamState.OPEN_DATA.equals(state)) {
				streamError(clientManager, HTTP2Error.Codes.PROTOCOL_ERROR);
				return true;
			}
			if (header.getPayloadLength() == 0) {
				onEndOfPayload(clientManager, header);
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
	protected void onEndOfPayload(ClientStreamsManager clientManager, HTTP2FrameHeader header) {
		if (frame instanceof HTTP2Priority) {
			HTTP2Priority prio = (HTTP2Priority)frame;
			if (prio.hasPriority()) {
				try {
					clientManager.addDependency(id, prio.getStreamDependency(),
						prio.getDependencyWeight(), prio.isExclusiveDependency());
				} catch (HTTP2Error e) {
					streamError(clientManager, e.getErrorCode());
					return;
				}
			}
		}
		
		switch (header.getType()) {
		case HTTP2FrameHeader.TYPE_HEADERS:
			if (StreamState.OPEN_TRAILERS.equals(state)) {
				// trailers
				if (!header.hasFlags(HTTP2Headers.FLAG_END_STREAM)) {
					// TODO error ??
				}
				if (header.hasFlags(HTTP2Headers.FLAG_END_HEADERS)) {
					// end of trailers
					// TODO how to signal to processor we have trailers ?
					clientManager.closeStream(this);
				}
			} else {
				if (header.hasFlags(HTTP2Headers.FLAG_END_HEADERS)) {
					// release decompression context
					clientManager.currentDecompressionStreamId = -1;
					if (header.hasFlags(HTTP2Headers.FLAG_END_STREAM)) {
						// end of headers and no body
						ctx.getRequest().setEntity(new EmptyEntity(null, ctx.getRequest().getHeaders()));
						clientManager.closeStream(this);
					} else {
						// end of headers, start data
						state = StreamState.OPEN_DATA;
					}
					// we can start processing the request
					clientManager.startProcessing(ctx, this);
					bodyConsumer = ctx.getRequest().getEntity().createConsumer(ctx.getRequest().getHeaders().getContentLength());
					break;
				}
				if (header.hasFlags(HTTP2Headers.FLAG_END_STREAM)) {
					// expect continuation but no data
					ctx.getRequest().setEntity(new EmptyEntity(null, ctx.getRequest().getHeaders()));
				}
				// continuation expected
				state = StreamState.OPEN_HEADERS;
			}
			break;
			
		case HTTP2FrameHeader.TYPE_CONTINUATION:
			if (header.hasFlags(HTTP2Continuation.FLAG_END_HEADERS)) {
				// release decompression context
				clientManager.currentDecompressionStreamId = -1;
				if (StreamState.OPEN_TRAILERS.equals(state)) {
					// end of trailers
					// TODO how to signal to processor we have trailers ?
					clientManager.closeStream(this);
				} else {
					// update state
					state = StreamState.OPEN_DATA;
					if (ctx.getRequest().getEntity() != null) {
						// body already set to empty => no body
						clientManager.startProcessing(ctx, this);
						clientManager.closeStream(this);
					} else {
						// end of headers, start processing and receive body
						clientManager.startProcessing(ctx, this);
						bodyConsumer = ctx.getRequest().getEntity()
							.createConsumer(ctx.getRequest().getHeaders().getContentLength());
					}
				}
			}
			break;
			
		case HTTP2FrameHeader.TYPE_DATA:
			clientManager.consumedConnectionRecvWindowSize(header.getPayloadLength());
			if (header.hasFlags(HTTP2Data.FLAG_END_STREAM)) {
				// end of body
				bodyConsumer.end();
				bodyConsumer = null;
				// no trailer
				clientManager.closeStream(this);
			} else {
				// body or trailers can follow
				// send a WINDOW_UPDATE as we consumed the data
				clientManager.sendFrame(new HTTP2WindowUpdate.Writer(id, header.getPayloadLength()), false);
			}
			break;
			
		case HTTP2FrameHeader.TYPE_RST_STREAM:
			// TODO if error ?
			clientManager.closeStream(this);
			break;
			
		case HTTP2FrameHeader.TYPE_WINDOW_UPDATE:
			clientManager.incrementStreamSendWindowSize(id, ((HTTP2WindowUpdate)frame).getIncrement());
			break;
			
		default:
			break;
		}
	}
	
	
	private void streamError(ClientStreamsManager clientManager, int errorCode) {
		HTTP2ResetStream.Writer frame = new HTTP2ResetStream.Writer(id, errorCode);
		clientManager.sendFrame(frame, false);
		if (bodyConsumer != null) {
			bodyConsumer.error(new IOException("HTTP/2 stream error"));
			bodyConsumer = null;
		}
		clientManager.closeStream(this);
	}
	
}
