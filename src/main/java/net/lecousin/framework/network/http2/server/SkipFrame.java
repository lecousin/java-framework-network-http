package net.lecousin.framework.network.http2.server;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;

class SkipFrame implements StreamHandler {

	private int payloadPos = 0;
	
	@Override
	public boolean startFrame(ClientStreamsManager clientManager, HTTP2FrameHeader header) {
		return true;
	}
	
	@Override
	public void consumeFramePayload(
		ClientStreamsManager clientManager, ByteBuffer data
	) {
		HTTP2FrameHeader header = (HTTP2FrameHeader)clientManager.client.getAttribute(HTTP2ServerProtocol.ATTRIBUTE_FRAME_HEADER);

		// just skip payload to be able to process next frame
		int expected = header.getPayloadLength() - payloadPos;
		if (data.remaining() < expected) {
			data.position(data.limit());
			clientManager.server.bufferCache.free(data);
			try { clientManager.client.waitForData(clientManager.server.getReceiveDataTimeout()); }
			catch (ClosedChannelException e) { /* ignore. */ }
			return;
		}
		data.position(data.position() + expected);
		clientManager.consumedConnectionRecvWindowSize(header.getPayloadLength());
		clientManager.server.endOfFrame(clientManager.client, data);
	}

}
