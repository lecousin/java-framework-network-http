package net.lecousin.framework.network.http2.server;

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
	public boolean startFrame(ClientStreamsManager clientManager, HTTP2FrameHeader header) {
		if (header.getPayloadLength() == 0) {
			if (clientManager.currentDecompressionStreamId == streamId)
				clientManager.currentDecompressionStreamId = -1;
		}

		// we need to consume using decompressionContext
		if (clientManager.currentDecompressionStreamId != -1 && clientManager.currentDecompressionStreamId != streamId) {
			HTTP2ServerProtocol.connectionError(clientManager.client, HTTP2Error.Codes.PROTOCOL_ERROR,
				"No concurrency is allowed for headers transmission");
			return false;
		}
		
		clientManager.currentDecompressionStreamId = streamId;
		HTTPRequest r = new HTTPRequest();
		frame = header.getType() == HTTP2FrameHeader.TYPE_HEADERS
			? new HTTP2Headers.Reader(header, clientManager.decompressionContext,
				r.getHeaders(), new HTTP2PseudoHeaderHandler.Request(r))
			: new HTTP2Continuation.Reader(header, clientManager.decompressionContext,
				r.getHeaders(), new HTTP2PseudoHeaderHandler.Request(r));
		payloadConsumer = frame.createConsumer();

		return true;
	}
	
	@Override
	protected void onEndOfPayload(ClientStreamsManager clientManager, HTTP2FrameHeader header) {
		clientManager.currentDecompressionStreamId = -1;
	}
}
