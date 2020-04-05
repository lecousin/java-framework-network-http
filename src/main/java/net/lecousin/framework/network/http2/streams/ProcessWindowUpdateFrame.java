package net.lecousin.framework.network.http2.streams;

import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.frame.HTTP2WindowUpdate;

public class ProcessWindowUpdateFrame extends StreamHandler.Default {

	@Override
	public boolean startFrame(StreamsManager manager, HTTP2FrameHeader header) {
		frame = new HTTP2WindowUpdate.Reader();
		payloadConsumer = frame.createConsumer();
		return true;
	}
	
	@Override
	protected void onEndOfPayload(StreamsManager manager, HTTP2FrameHeader header) {
		manager.incrementStreamSendWindowSize(header.getStreamId(), ((HTTP2WindowUpdate)frame).getIncrement());
	}
	
}
