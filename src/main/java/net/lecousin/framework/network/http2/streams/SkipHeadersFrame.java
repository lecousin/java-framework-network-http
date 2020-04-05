package net.lecousin.framework.network.http2.streams;

import java.io.IOException;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.HTTP2PseudoHeaderHandler;
import net.lecousin.framework.network.http2.frame.HTTP2Continuation;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.frame.HTTP2Headers;
import net.lecousin.framework.network.mime.header.MimeHeaders;

class SkipHeadersFrame extends StreamHandler.Default {
	
	private int streamId;
	
	SkipHeadersFrame(int streamId) {
		this.streamId = streamId;
	}
	
	@Override
	public boolean startFrame(StreamsManager manager, HTTP2FrameHeader header) {
		if (header.getPayloadLength() == 0) {
			if (manager.currentDecompressionStreamId == streamId)
				manager.currentDecompressionStreamId = -1;
		}

		// we need to consume using decompressionContext
		if (manager.currentDecompressionStreamId != -1 && manager.currentDecompressionStreamId != streamId) {
			manager.connectionError(HTTP2Error.Codes.PROTOCOL_ERROR, "No concurrency is allowed for headers transmission");
			return false;
		}
		
		manager.currentDecompressionStreamId = streamId;
		MimeHeaders headers = new MimeHeaders();
		frame = header.getType() == HTTP2FrameHeader.TYPE_HEADERS
			? new HTTP2Headers.Reader(header, manager.decompressionContext, headers, new HTTP2PseudoHeaderHandler.IgnoreAll())
			: new HTTP2Continuation.Reader(header, manager.decompressionContext, headers, new HTTP2PseudoHeaderHandler.IgnoreAll());
		payloadConsumer = frame.createConsumer();

		return true;
	}
	
	@Override
	protected void onEndOfPayload(StreamsManager manager, HTTP2FrameHeader header) {
		manager.currentDecompressionStreamId = -1;
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
