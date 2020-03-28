package net.lecousin.framework.network.http2.streams;

import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.HTTP2PseudoHeaderHandler;
import net.lecousin.framework.network.http2.frame.HTTP2Continuation;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.frame.HTTP2Headers;

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
		HTTPRequest r = new HTTPRequest();
		frame = header.getType() == HTTP2FrameHeader.TYPE_HEADERS
			? new HTTP2Headers.Reader(header, manager.decompressionContext,
				r.getHeaders(), new HTTP2PseudoHeaderHandler.Request(r))
			: new HTTP2Continuation.Reader(header, manager.decompressionContext,
				r.getHeaders(), new HTTP2PseudoHeaderHandler.Request(r));
		payloadConsumer = frame.createConsumer();

		return true;
	}
	
	@Override
	protected void onEndOfPayload(StreamsManager manager, HTTP2FrameHeader header) {
		manager.currentDecompressionStreamId = -1;
	}
}
