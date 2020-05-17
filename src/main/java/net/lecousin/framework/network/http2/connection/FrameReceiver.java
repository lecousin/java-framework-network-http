package net.lecousin.framework.network.http2.connection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.HTTP2PseudoHeaderHandler;
import net.lecousin.framework.network.http2.connection.HTTP2Stream.State;
import net.lecousin.framework.network.http2.frame.HTTP2Continuation;
import net.lecousin.framework.network.http2.frame.HTTP2Data;
import net.lecousin.framework.network.http2.frame.HTTP2Frame;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.frame.HTTP2GoAway;
import net.lecousin.framework.network.http2.frame.HTTP2Headers;
import net.lecousin.framework.network.http2.frame.HTTP2Ping;
import net.lecousin.framework.network.http2.frame.HTTP2Priority;
import net.lecousin.framework.network.http2.frame.HTTP2ResetStream;
import net.lecousin.framework.network.http2.frame.HTTP2Settings;
import net.lecousin.framework.network.http2.frame.HTTP2UnknownFrame;
import net.lecousin.framework.network.http2.frame.HTTP2WindowUpdate;
import net.lecousin.framework.network.http2.hpack.HPackDecompress;
import net.lecousin.framework.network.mime.header.MimeHeaders;

class FrameReceiver {

	private enum FrameState {
		START, HEADER, PAYLOAD
	}

	private HTTP2Connection conn;
	private FrameState state = FrameState.START;
	private HTTP2FrameHeader header = null;
	private HTTP2FrameHeader.Consumer headerConsumer = null;
	private HTTP2Stream stream = null;
	private HTTP2Frame.Reader frame = null;
	private HTTP2Frame.Reader.Consumer payloadConsumer;
	private int payloadPos;

	FrameReceiver(HTTP2Connection connection) {
		conn = connection;
	}
	
	void consume(ByteBuffer data) {
		switch (state) {
		case START:
			startConsumeFrameHeader(data);
			break;
		case HEADER:
			continueConsumeFrameHeader(data);
			break;
		default: // not possible
		case PAYLOAD:
			continueConsumeFramePayload(data);
			break;
		}
	}
	
	private void startConsumeFrameHeader(ByteBuffer data) {
		if (conn.logger.trace())
			conn.logger.trace("Start receiving new frame from " + conn.remote);
		header = new HTTP2FrameHeader();
		headerConsumer = header.createConsumer();
		continueConsumeFrameHeader(data);
	}
	
	
	private void continueConsumeFrameHeader(ByteBuffer data) {
		boolean headerReady = headerConsumer.consume(data);
		if (!headerReady) {
			state = FrameState.HEADER;
			conn.bufferCache.free(data);
			conn.acceptNewDataFromRemote();
			return;
		}
		headerConsumer = null;
		try {
			processHeader();
		} catch (HTTP2Error e) {
			conn.error(e, data);
			return;
		}
		if (header.getPayloadLength() == 0) {
			endOfFrame(data);
			return;
		}
		payloadConsumer = frame.createConsumer();
		payloadPos = 0;
		continueConsumeFramePayload(data);
	}
	
	private void continueConsumeFramePayload(ByteBuffer data) {
		int startPos = data.position();
		int endPos = payloadPos + data.remaining() > header.getPayloadLength()
			? startPos + (header.getPayloadLength() - payloadPos) : data.limit(); 
		AsyncSupplier<Boolean, HTTP2Error> consumption = payloadConsumer.consume(data);
		consumption.thenDoOrStart("HTTP/2 frame payload consumed", Priority.NORMAL, () -> {
			HTTP2Error err = null;
			if (!consumption.isSuccessful()) {
				if (consumption.hasError())
					err = consumption.getError();
				else
					err = new HTTP2Error(header.getStreamId(), HTTP2Error.Codes.CANCEL, null);
				conn.logger.error("Error consuming frame " + header + " after " + (data.position() - startPos) + " byte(s)", err);
			} else if (data.position() != endPos) {
				conn.logger.error("Payload consumer " + payloadConsumer + ": data position is "
					+ data.position() + ", expected is " + endPos);
				err = new HTTP2Error(header.getStreamId(), HTTP2Error.Codes.INTERNAL_ERROR, "Payload not correctly consumed");
			} else {
				payloadPos += endPos - startPos;
				if (payloadPos == header.getPayloadLength() && !consumption.getResult().booleanValue())
					err = new HTTP2Error(header.getStreamId(), HTTP2Error.Codes.INTERNAL_ERROR,
						"Consumer expect more than the payload");
			}
			if (err != null) {
				data.position(endPos);
				conn.error(err, data);
				return;
			}
			if (payloadPos == header.getPayloadLength()) {
				endOfFrame(data);
				return;
			}
			state = FrameState.PAYLOAD;
			conn.bufferCache.free(data);
			conn.acceptNewDataFromRemote();
		});
	}

	private void processHeader() throws HTTP2Error {
		if (conn.logger.debug()) conn.logger.debug("Received frame header: " + header);
		if (header.getPayloadLength() > conn.getMaximumReceivedPayloadLength())
			throw new HTTP2Error(0, HTTP2Error.Codes.ENHANCE_YOUR_CALM, "I asked you a maximum frame size of "
				+ conn.getMaximumReceivedPayloadLength() + " but I received a frame of " + header.getPayloadLength());
		if (header.getStreamId() == 0) {
			// this is a frame on connection stream
			processConnectionStreamHeader();
		} else {
			// this is a frame on a data stream
			stream = conn.getDataStream(header.getStreamId());
			processDataStreamHeader();
		}
	}
	
	private void processConnectionStreamHeader() throws HTTP2Error {
		switch (header.getType()) {
		case HTTP2FrameHeader.TYPE_HEADERS:
		case HTTP2FrameHeader.TYPE_CONTINUATION:
		case HTTP2FrameHeader.TYPE_DATA:
		case HTTP2FrameHeader.TYPE_RST_STREAM:
		case HTTP2FrameHeader.TYPE_PRIORITY:
			// MUST NOT be on stream ID 0
			throw new HTTP2Error(0, HTTP2Error.Codes.PROTOCOL_ERROR, "frame must not be on stream 0");

		case HTTP2FrameHeader.TYPE_SETTINGS:
			if (header.hasFlags(HTTP2Settings.FLAG_ACK)) {
				// acknowledge of settings, the payload MUST be empty
				if (header.getPayloadLength() != 0)
					throw new HTTP2Error(0, HTTP2Error.Codes.FRAME_SIZE_ERROR, "settings ack must have empty payload");
			} else if ((header.getPayloadLength() % 6) != 0) {
				throw new HTTP2Error(0, HTTP2Error.Codes.FRAME_SIZE_ERROR);
			}
			frame = new HTTP2Settings.Reader(header);
			break;

		case HTTP2FrameHeader.TYPE_PING:
			if (header.getPayloadLength() != HTTP2Ping.OPAQUE_DATA_LENGTH)
				throw new HTTP2Error(0, HTTP2Error.Codes.FRAME_SIZE_ERROR, "Invalid ping size");
			frame = new HTTP2Ping.Reader();
			break;
			
		case HTTP2FrameHeader.TYPE_GOAWAY:
			if (header.getPayloadLength() < HTTP2GoAway.MINIMUM_PAYLOAD_LENGTH)
				throw new HTTP2Error(0, HTTP2Error.Codes.FRAME_SIZE_ERROR, "Invalid goaway size");
			frame = new HTTP2GoAway.Reader(header);
			break;
			
		case HTTP2FrameHeader.TYPE_WINDOW_UPDATE:
			if (header.getPayloadLength() != HTTP2WindowUpdate.LENGTH)
				throw new HTTP2Error(0, HTTP2Error.Codes.FRAME_SIZE_ERROR, "Invalid window update size");
			frame = new HTTP2WindowUpdate.Reader();
			break;
			
		default:
			// unknown
			frame = new HTTP2UnknownFrame.Reader(header);
			break;
		}
	}
	
	private void processDataStreamHeader() throws HTTP2Error {
		HPackDecompress decompress = null;
		
		// check frame validity
		switch (header.getType()) {
		case HTTP2FrameHeader.TYPE_SETTINGS:
		case HTTP2FrameHeader.TYPE_PING:
		case HTTP2FrameHeader.TYPE_GOAWAY:
			throw new HTTP2Error(0, HTTP2Error.Codes.PROTOCOL_ERROR, "frame must be on stream 0");

		case HTTP2FrameHeader.TYPE_PRIORITY:
			if (header.getPayloadLength() != HTTP2Priority.LENGTH)
				throw new HTTP2Error(0, HTTP2Error.Codes.FRAME_SIZE_ERROR, "Invalid priority frame size");
			// always allowed
			frame = new HTTP2Priority.Reader();
			break;
			
		case HTTP2FrameHeader.TYPE_RST_STREAM:
			if (header.getPayloadLength() != HTTP2ResetStream.LENGTH)
				throw new HTTP2Error(0, HTTP2Error.Codes.FRAME_SIZE_ERROR, "Invalid reset stream size");
			break;
			
		case HTTP2FrameHeader.TYPE_WINDOW_UPDATE:
			if (header.getPayloadLength() != HTTP2WindowUpdate.LENGTH)
				throw new HTTP2Error(0, HTTP2Error.Codes.FRAME_SIZE_ERROR, "Invalid window update size");
			break;
			
		case HTTP2FrameHeader.TYPE_HEADERS:
			decompress = conn.takeDecompressor(header.getStreamId());
			break;

		case HTTP2FrameHeader.TYPE_CONTINUATION:
			decompress = conn.reuseDecompressor(header.getStreamId());
			break;
			
		case HTTP2FrameHeader.TYPE_DATA:
			break;
			
		default:
			break;
			
		}
		
		if (stream == null) {
			// it may be a request to open a new stream, or remaining frames on a previous stream
			if ((header.getStreamId() % 2) == (conn.isClientMode() ? 1 : 0) ||
				(header.getStreamId() <= conn.getLastRemoteStreamId())) {
				// it may be remaining frames sent before we sent a reset stream to the remote
				// we need to process it as it may change connection contexts (i.e. decompression context)
				processRemainingFrameOnClosedStream(decompress);
				return;
			}
			// this is a new stream from the remote
			processOpeningDataStream(decompress);
			return;
		}

		// existing stream
		switch (header.getType()) {
		case HTTP2FrameHeader.TYPE_RST_STREAM:
			frame = new HTTP2ResetStream.Reader();
			break;
			
		case HTTP2FrameHeader.TYPE_WINDOW_UPDATE:
			if (header.getPayloadLength() != HTTP2WindowUpdate.LENGTH)
				throw new HTTP2Error(0, HTTP2Error.Codes.FRAME_SIZE_ERROR, "Invalid window update size");
			frame = new HTTP2WindowUpdate.Reader();
			break;
			
		case HTTP2FrameHeader.TYPE_HEADERS:
			// headers can be received at the beginning or after data to start trailers
			if (!State.WAITING_HEADERS.equals(stream.state) && !State.DATA.equals(stream.state))
				throw new HTTP2Error(0, HTTP2Error.Codes.PROTOCOL_ERROR, "Invalid stream state");
			frame = new HTTP2Headers.Reader(header, decompress, stream.getReceivedHeaders(), stream.createPseudoHeaderHandler());
			break;
			
		case HTTP2FrameHeader.TYPE_CONTINUATION:
			if (!State.HEADERS.equals(stream.state) && !State.TRAILERS.equals(stream.state))
				throw new HTTP2Error(0, HTTP2Error.Codes.PROTOCOL_ERROR, "Invalid stream state");
			frame = new HTTP2Continuation.Reader(header, decompress, stream.getReceivedHeaders(), stream.createPseudoHeaderHandler());
			break;
			
		case HTTP2FrameHeader.TYPE_PRIORITY:
			break;
			
		case HTTP2FrameHeader.TYPE_DATA:
			if (!State.DATA.equals(stream.state))
				throw new HTTP2Error(0, HTTP2Error.Codes.PROTOCOL_ERROR, "Invalid stream state");
			AsyncConsumer<ByteBuffer, IOException> bodyConsumer = stream.getBodyConsumer();
			if (bodyConsumer == null) {
				conn.logger.error("No body consumer for DATA");
				conn.error(new HTTP2Error(stream.getStreamId(), HTTP2Error.Codes.INTERNAL_ERROR), null);
				conn.decrementConnectionRecvWindowSize(header.getPayloadLength());
				frame = new HTTP2UnknownFrame.Reader(header);
				break;
			}
			frame = new HTTP2Data.Reader(header, bodyConsumer);
			break;
			
		default:
			// unknown
			frame = new HTTP2UnknownFrame.Reader(header);
			break;
		}
	}
	
	private void processRemainingFrameOnClosedStream(HPackDecompress decompress) {
		stream = new HTTP2ClosedStream(conn, header.getStreamId());
		switch (header.getType()) {
		case HTTP2FrameHeader.TYPE_HEADERS:
			frame = new HTTP2Headers.Reader(header, decompress, new MimeHeaders(), new HTTP2PseudoHeaderHandler.IgnoreAll());
			break;
		case HTTP2FrameHeader.TYPE_CONTINUATION:
			frame = new HTTP2Continuation.Reader(header, decompress, new MimeHeaders(), new HTTP2PseudoHeaderHandler.IgnoreAll());
			break;
		case HTTP2FrameHeader.TYPE_PRIORITY:
			break;
			// TODO data to update window
		default:
			frame = new HTTP2UnknownFrame.Reader(header);
			break;
		}
	}
	
	private void processOpeningDataStream(HPackDecompress decompress) throws HTTP2Error {
		switch (header.getType()) {
		case HTTP2FrameHeader.TYPE_HEADERS:
		// PUSH_PROMISE
		case HTTP2FrameHeader.TYPE_PRIORITY:
			stream = conn.openRemoteStream(header.getStreamId());
			break;
		default:
			throw new HTTP2Error(0, HTTP2Error.Codes.PROTOCOL_ERROR, "Invalid frame on idle stream");
		}
		// handle frame type
		switch (header.getType()) {
		case HTTP2FrameHeader.TYPE_HEADERS:
			frame = new HTTP2Headers.Reader(header, decompress, stream.getReceivedHeaders(), stream.createPseudoHeaderHandler());
			stream.state = State.HEADERS;
			break;
		default:
		case HTTP2FrameHeader.TYPE_PRIORITY:
			stream.state = State.WAITING_HEADERS;
			break;
		}
	}
	
	private void endOfFrame(ByteBuffer data) {
		if (header.getStreamId() == 0)
			processConnectionFrame();
		else
			stream.processFrame(header, frame);
		
		header = null;
		stream = null;
		frame = null;
		payloadConsumer = null;
		if (!data.hasRemaining()) {
			state = FrameState.START;
			conn.bufferCache.free(data);
			conn.acceptNewDataFromRemote();
			return;
		}
		// start a new task to avoid recursivity
		Task.cpu("Consume HTTP/2 frame", t -> {
			if (conn.isClosing())
				return null;
			startConsumeFrameHeader(data);
			return null;
		}).start();
	}
	
	private void processConnectionFrame() {
		switch (header.getType()) {
		case HTTP2FrameHeader.TYPE_SETTINGS:
			if (header.hasFlags(HTTP2Settings.FLAG_ACK)) {
				// TODO
			} else {
				conn.applyRemoteSettings((HTTP2Settings.Reader)frame);
			}
			break;
			
		case HTTP2FrameHeader.TYPE_PING:
			if ((header.getFlags() & HTTP2Ping.FLAG_ACK) != 0)
				conn.pinger.pingResponse(((HTTP2Ping)frame).getOpaqueData());
			else
				conn.sendFrame(new HTTP2Ping.Writer(((HTTP2Ping)frame).getOpaqueData(), true), false, false);
			break;

		case HTTP2FrameHeader.TYPE_GOAWAY:
			HTTP2GoAway f = (HTTP2GoAway)frame;
			if (f.getErrorCode() != HTTP2Error.Codes.NO_ERROR || conn.logger.debug()) {
				String msg = "GOAWAY: last stream id = " + f.getLastStreamID()
					+ ", error = " + f.getErrorCode()
					+ ", message = "
					+ (f.getDebugMessage() == null ? "null" : new String(f.getDebugMessage(), StandardCharsets.US_ASCII));
				if (f.getErrorCode() != HTTP2Error.Codes.NO_ERROR)
					conn.logger.error(msg);
				else
					conn.logger.debug(msg);
			}
			conn.remote.close();
			break;
			
		case HTTP2FrameHeader.TYPE_WINDOW_UPDATE:
			conn.incrementConnectionSendWindowSize(((HTTP2WindowUpdate)frame).getIncrement());
			break;
			
		default:
			break;
		}
	}
}
