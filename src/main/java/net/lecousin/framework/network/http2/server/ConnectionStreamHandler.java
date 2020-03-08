package net.lecousin.framework.network.http2.server;

import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.frame.HTTP2Frame;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.frame.HTTP2Ping;
import net.lecousin.framework.network.http2.frame.HTTP2Settings;
import net.lecousin.framework.network.http2.frame.HTTP2WindowUpdate;

class ConnectionStreamHandler extends StreamHandler.Default {

	/** Start processing a frame. Return false if nothing else should be done. */
	@Override
	public boolean startFrame(ClientStreamsManager clientManager, HTTP2FrameHeader header) {
		payloadPos = 0;
		
		switch (header.getType()) {
		case HTTP2FrameHeader.TYPE_HEADERS:
		case HTTP2FrameHeader.TYPE_CONTINUATION:
		case HTTP2FrameHeader.TYPE_DATA:
		case HTTP2FrameHeader.TYPE_RST_STREAM:
		case HTTP2FrameHeader.TYPE_PRIORITY:
			// MUST NOT be on stream ID 0
			HTTP2ServerProtocol.connectionError(clientManager.client, HTTP2Error.Codes.PROTOCOL_ERROR, "frame must not be on stream 0");
			return false;
			
		case HTTP2FrameHeader.TYPE_SETTINGS:
			if (header.hasFlags(HTTP2Settings.FLAG_ACK)) {
				// acknowledge of settings, the payload MUST be empty
				if (header.getPayloadLength() != 0) {
					HTTP2ServerProtocol.connectionError(clientManager.client, HTTP2Error.Codes.FRAME_SIZE_ERROR, null);
					return false;
				}
				// TODO ?
				return true;
			}
			// the client ask to change settings as soon as possible
			if (header.getPayloadLength() == 0) {
				// nothing to change... ok, let's do nothing
				onEndOfPayload(clientManager, header);
				return true;
			}
			try {
				frame = new HTTP2Settings.Reader(header);
			} catch (HTTP2Error e) {
				HTTP2ServerProtocol.connectionError(clientManager.client, e.getErrorCode(), e.getMessage());
				return false;
			}
			payloadConsumer = frame.createConsumer();
			return true;
			
		case HTTP2FrameHeader.TYPE_PING:
			if (header.getPayloadLength() != HTTP2Ping.OPAQUE_DATA_LENGTH) {
				HTTP2ServerProtocol.connectionError(clientManager.client, HTTP2Error.Codes.FRAME_SIZE_ERROR, null);
				return false;
			}
			frame = new HTTP2Ping.Reader();
			payloadConsumer = frame.createConsumer();
			return true;
			
		case HTTP2FrameHeader.TYPE_GOAWAY:
			clientManager.client.close();
			return false;
			
		case HTTP2FrameHeader.TYPE_WINDOW_UPDATE:
			if (header.getPayloadLength() != HTTP2WindowUpdate.LENGTH) {
				HTTP2ServerProtocol.connectionError(clientManager.client, HTTP2Error.Codes.FRAME_SIZE_ERROR, null);
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
	protected void onEndOfPayload(ClientStreamsManager clientManager, HTTP2FrameHeader header) {
		switch (header.getType()) {
		case HTTP2FrameHeader.TYPE_SETTINGS:
			clientManager.applySettings((HTTP2Settings.Reader)frame);
			break;
		case HTTP2FrameHeader.TYPE_PING:
			clientManager.sendFrame(new HTTP2Ping.Writer(((HTTP2Ping)frame).getOpaqueData(), true), false);
			break;
		case HTTP2FrameHeader.TYPE_WINDOW_UPDATE:
			clientManager.incrementConnectionSendWindowSize(((HTTP2WindowUpdate)frame).getIncrement());
			break;
		default:
			break;
		}
	}

}
