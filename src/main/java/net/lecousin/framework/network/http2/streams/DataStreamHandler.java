package net.lecousin.framework.network.http2.streams;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.frame.HTTP2Continuation;
import net.lecousin.framework.network.http2.frame.HTTP2Data;
import net.lecousin.framework.network.http2.frame.HTTP2Frame;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.frame.HTTP2Headers;
import net.lecousin.framework.network.http2.frame.HTTP2Priority;
import net.lecousin.framework.network.http2.frame.HTTP2ResetStream;
import net.lecousin.framework.network.http2.frame.HTTP2WindowUpdate;

class DataStreamHandler extends StreamHandler.Default {
	
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
	
	public int getStreamId() {
		return id;
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
				manager.connectionError(e.getErrorCode(), e.getMessage());
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
				streamError(manager, HTTP2Error.Codes.PROTOCOL_ERROR);
				return true;
			}
			if (header.getPayloadLength() == 0) {
				onEndOfPayload(manager, header);
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
	protected void onEndOfPayload(StreamsManager manager, HTTP2FrameHeader header) {
		if (frame instanceof HTTP2Priority) {
			HTTP2Priority prio = (HTTP2Priority)frame;
			if (prio.hasPriority()) {
				try {
					manager.addDependency(id, prio.getStreamDependency(),
						prio.getDependencyWeight(), prio.isExclusiveDependency());
				} catch (HTTP2Error e) {
					streamError(manager, e.getErrorCode());
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
					manager.closeStream(this);
				}
			} else {
				if (header.hasFlags(HTTP2Headers.FLAG_END_HEADERS)) {
					// release decompression context
					manager.currentDecompressionStreamId = -1;
					if (header.hasFlags(HTTP2Headers.FLAG_END_STREAM)) {
						// end of headers and no body
						dataHandler.emptyEntityReceived();
						noBody = true;
						manager.closeStream(this);
					} else {
						// end of headers, start data
						state = StreamState.OPEN_DATA;
					}
					try {
						bodyConsumer = dataHandler.endOfHeaders();
					} catch (Exception e) {
						manager.getLogger().error("Error processing headers", e);
						manager.closeStream(this);
					}
					break;
				}
				if (header.hasFlags(HTTP2Headers.FLAG_END_STREAM)) {
					// expect continuation but no data
					dataHandler.emptyEntityReceived();
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
					// TODO how to signal to processor we have trailers ?
					manager.closeStream(this);
				} else {
					// update state
					state = StreamState.OPEN_DATA;
					try {
						bodyConsumer = dataHandler.endOfHeaders();
					} catch (Exception e) {
						manager.getLogger().error("Error processing headers", e);
						manager.closeStream(this);
						break;
					}
					if (noBody) {
						manager.closeStream(this);
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
				dataHandler.endOfBody();
				// no trailer
				dataHandler.endOfTrailers();
				manager.closeStream(this);
			} else {
				// body or trailers can follow
				// send a WINDOW_UPDATE as we consumed the data
				manager.sendFrame(new HTTP2WindowUpdate.Writer(id, header.getPayloadLength()), false);
			}
			break;
			
		case HTTP2FrameHeader.TYPE_RST_STREAM:
			// TODO if error ?
			manager.closeStream(this);
			break;
			
		case HTTP2FrameHeader.TYPE_WINDOW_UPDATE:
			manager.incrementStreamSendWindowSize(id, ((HTTP2WindowUpdate)frame).getIncrement());
			break;
			
		default:
			break;
		}
	}
	
	
	private void streamError(StreamsManager manager, int errorCode) {
		HTTP2ResetStream.Writer frame = new HTTP2ResetStream.Writer(id, errorCode);
		manager.sendFrame(frame, false);
		if (bodyConsumer != null) {
			bodyConsumer.error(new IOException("HTTP/2 stream error"));
			bodyConsumer = null;
		}
		manager.closeStream(this);
	}
	
}
