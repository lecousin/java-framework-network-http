package net.lecousin.framework.network.http2.connection;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.network.http2.HTTP2PseudoHeaderHandler;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.frame.HTTP2Priority;
import net.lecousin.framework.network.http2.frame.HTTP2ResetStream;
import net.lecousin.framework.network.http2.frame.HTTP2WindowUpdate;
import net.lecousin.framework.network.mime.header.MimeHeaders;

public class HTTP2ClosedStream extends HTTP2Stream {

	public HTTP2ClosedStream(HTTP2Connection conn, int id) {
		super(conn, id);
	}
	
	@Override
	protected void errorReceived(int errorCode) {
		// ignore
	}

	@Override
	protected void processPriority(HTTP2Priority prio) {
		// ignore
	}
	
	@Override
	protected void processHeaders(HTTP2FrameHeader header) {
		// ignore
	}
	
	@Override
	protected void processContinuation(HTTP2FrameHeader header) {
		// ignore
	}
	
	@Override
	protected void processData(HTTP2FrameHeader header) {
		// ignore
	}
	
	@Override
	protected void processResetStream(HTTP2ResetStream.Reader frame) {
		// ignore
	}
	
	@Override
	protected void processWindowUpdate(HTTP2WindowUpdate.Reader frame) {
		// ignore
	}

	@Override
	public HTTP2PseudoHeaderHandler createPseudoHeaderHandler() {
		return new HTTP2PseudoHeaderHandler.IgnoreAll();
	}

	@Override
	public MimeHeaders getReceivedHeaders() {
		return new MimeHeaders();
	}

	@Override
	public void endOfHeadersWithoutBody() {
		// nothing
	}

	@Override
	public AsyncConsumer<ByteBuffer, IOException> endOfHeadersWithBody() {
		// nothing
		return null;
	}

	@Override
	public void endOfBody() {
		// nothing
	}

	@Override
	public void endOfTrailers() {
		// nothing
	}
}
