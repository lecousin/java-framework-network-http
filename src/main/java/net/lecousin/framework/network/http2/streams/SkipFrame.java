package net.lecousin.framework.network.http2.streams;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;

class SkipFrame implements StreamHandler {

	private int payloadPos = 0;
	
	@Override
	public boolean startFrame(StreamsManager manager, HTTP2FrameHeader header) {
		return true;
	}
	
	@Override
	public void consumeFramePayload(StreamsManager manager, ByteBuffer data, Async<IOException> onConsumed) {
		HTTP2FrameHeader header = manager.getCurrentFrameHeader();

		// just skip payload to be able to process next frame
		int expected = header.getPayloadLength() - payloadPos;
		if (data.remaining() < expected) {
			data.position(data.limit());
			onConsumed.unblock();
			return;
		}
		data.position(data.position() + expected);
		manager.consumedConnectionRecvWindowSize(header.getPayloadLength());
		manager.endOfFrame(data, onConsumed);
	}

}
