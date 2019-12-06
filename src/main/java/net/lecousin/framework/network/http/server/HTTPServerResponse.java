package net.lecousin.framework.network.http.server;

import java.io.IOException;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.network.http.HTTPResponse;

/**
 * HTTPResponse to be sent, with few additional attributes to control how it is sent.
 */
public class HTTPServerResponse extends HTTPResponse {

	/** Indicates if the connection with the client must be closed once sent. */
	private boolean forceClose = false;
	
	/** Indicates that there is no content, so no Content-Length should be sent, no chunked transfer...
	 * This may be useful for streaming such as SSE.
	 */
	private boolean forceNoContent = false;
	
	/** SynchronizationPoint allowing to know when the response has been sent to the network. */
	private Async<IOException> sent = new Async<>();

	/** Indicates if the connection with the client must be closed once sent. */
	public boolean isForceClose() {
		return forceClose;
	}

	/** Indicates if the connection with the client must be closed once sent. */
	public void setForceClose(boolean forceClose) {
		this.forceClose = forceClose;
	}

	/** Indicates that there is no content, so no Content-Length should be sent, no chunked transfer...
	 * This may be useful for streaming such as SSE.
	 */
	public boolean isForceNoContent() {
		return forceNoContent;
	}

	/** Indicates that there is no content, so no Content-Length should be sent, no chunked transfer...
	 * This may be useful for streaming such as SSE.
	 */
	public void setForceNoContent(boolean forceNoContent) {
		this.forceNoContent = forceNoContent;
	}

	/** SynchronizationPoint allowing to know when the response has been sent to the network. */
	public Async<IOException> getSent() {
		return sent;
	}
	
	
	
}
