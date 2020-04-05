package net.lecousin.framework.network.http2.streams;

import java.io.IOException;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.frame.HTTP2WindowUpdate;

public class ProcessWindowUpdateFrame extends StreamHandler.Default {

	public ProcessWindowUpdateFrame(int streamId) {
		this.streamId = streamId;
	}
	
	private int streamId;
	
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

	@Override
	public int getStreamId() {
		return streamId;
	}

	@Override
	protected void error(HTTP2Error error, StreamsManager manager, Async<IOException> onConsumed) {
		onConsumed.error(IO.error(error));
	}
	
}
