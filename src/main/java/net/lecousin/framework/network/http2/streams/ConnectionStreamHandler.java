package net.lecousin.framework.network.http2.streams;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.frame.HTTP2Frame;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.frame.HTTP2GoAway;
import net.lecousin.framework.network.http2.frame.HTTP2Ping;
import net.lecousin.framework.network.http2.frame.HTTP2Settings;
import net.lecousin.framework.network.http2.frame.HTTP2WindowUpdate;

class ConnectionStreamHandler extends StreamHandler.Default {
	
	@Override
	public int getStreamId() {
		return 0;
	}

	/** Start processing a frame. Return false if nothing else should be done. */
	@Override
	public boolean startFrame(StreamsManager manager, HTTP2FrameHeader header) {
		payloadPos = 0;
		
		switch (header.getType()) {
		case HTTP2FrameHeader.TYPE_HEADERS:
		case HTTP2FrameHeader.TYPE_CONTINUATION:
		case HTTP2FrameHeader.TYPE_DATA:
		case HTTP2FrameHeader.TYPE_RST_STREAM:
		case HTTP2FrameHeader.TYPE_PRIORITY:
			// MUST NOT be on stream ID 0
			manager.connectionError(HTTP2Error.Codes.PROTOCOL_ERROR, "frame must not be on stream 0");
			return false;
			
		case HTTP2FrameHeader.TYPE_SETTINGS:
			if (header.hasFlags(HTTP2Settings.FLAG_ACK)) {
				// acknowledge of settings, the payload MUST be empty
				if (header.getPayloadLength() != 0) {
					manager.connectionError(HTTP2Error.Codes.FRAME_SIZE_ERROR, "settings ack must have empty payload");
					return false;
				}
				// TODO ?
				return true;
			}
			// the client ask to change settings as soon as possible
			if (header.getPayloadLength() == 0) {
				// nothing to change... but it may be the first one
				manager.applyRemoteSettings(new HTTP2Settings());
				onEndOfPayload(manager, header);
				return true;
			}
			try {
				frame = new HTTP2Settings.Reader(header);
			} catch (HTTP2Error e) {
				manager.connectionError(e.getErrorCode(), "Error reading settings: " + e.getMessage());
				return false;
			}
			payloadConsumer = frame.createConsumer();
			return true;
			
		case HTTP2FrameHeader.TYPE_PING:
			if (header.getPayloadLength() != HTTP2Ping.OPAQUE_DATA_LENGTH) {
				manager.connectionError(HTTP2Error.Codes.FRAME_SIZE_ERROR, "Invalid ping size");
				return false;
			}
			frame = new HTTP2Ping.Reader();
			payloadConsumer = frame.createConsumer();
			return true;
			
		case HTTP2FrameHeader.TYPE_GOAWAY:
			if (header.getPayloadLength() < HTTP2GoAway.MINIMUM_PAYLOAD_LENGTH) {
				manager.connectionError(HTTP2Error.Codes.FRAME_SIZE_ERROR, "Invalid goaway size");
				return false;
			}
			frame = new HTTP2GoAway.Reader(header);
			payloadConsumer = frame.createConsumer();
			return true;
			
		case HTTP2FrameHeader.TYPE_WINDOW_UPDATE:
			if (header.getPayloadLength() != HTTP2WindowUpdate.LENGTH) {
				manager.connectionError(HTTP2Error.Codes.FRAME_SIZE_ERROR, "Invalid window update size");
				return false;
			}
			frame = new HTTP2WindowUpdate.Reader();
			payloadConsumer = frame.createConsumer();
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
		switch (header.getType()) {
		case HTTP2FrameHeader.TYPE_SETTINGS:
			manager.applyRemoteSettings((HTTP2Settings.Reader)frame);
			break;
		case HTTP2FrameHeader.TYPE_PING:
			if ((header.getFlags() & HTTP2Ping.FLAG_ACK) != 0)
				manager.pingResponse(((HTTP2Ping)frame).getOpaqueData());
			else
				manager.sendFrame(new HTTP2Ping.Writer(((HTTP2Ping)frame).getOpaqueData(), true), false);
			break;
		case HTTP2FrameHeader.TYPE_WINDOW_UPDATE:
			manager.incrementConnectionSendWindowSize(((HTTP2WindowUpdate)frame).getIncrement());
			break;
		case HTTP2FrameHeader.TYPE_GOAWAY:
			HTTP2GoAway f = (HTTP2GoAway)frame;
			if (f.getErrorCode() != HTTP2Error.Codes.NO_ERROR || manager.getLogger().debug()) {
				String msg = "GOAWAY: last stream id = " + f.getLastStreamID()
					+ ", error = " + f.getErrorCode()
					+ ", message = "
					+ (f.getDebugMessage() == null ? "null" : new String(f.getDebugMessage(), StandardCharsets.US_ASCII));
				if (f.getErrorCode() != HTTP2Error.Codes.NO_ERROR)
					manager.getLogger().error(msg);
				else
					manager.getLogger().debug(msg);
			}
			manager.remote.close();
			break;
		default:
			break;
		}
	}
	
	@Override
	protected void error(HTTP2Error error, StreamsManager manager, Async<IOException> onConsumed) {
		manager.connectionError(error.getErrorCode(), error.getMessage());
		onConsumed.error(IO.error(error));
	}

}
