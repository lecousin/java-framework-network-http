package net.lecousin.framework.network.http.server;

import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.network.http.HTTPResponse;

/**
 * HTTPResponse to be sent, with few additional attributes to control how it is sent.
 */
public class HTTPServerResponse extends HTTPResponse {

	/** Indicates if the connection with the client must be closed once sent. */
	public boolean forceClose = false;
	
	/** Indicates that there is no content, so no Content-Length should be sent, no chunked transfer...
	 * This may be useful for streaming such as SSE.
	 */
	public boolean forceNoContent = false;
	
	/** SynchronizationPoint allowing to know when the response has been sent to the network. */
	public SynchronizationPoint<Exception> sent = new SynchronizationPoint<>();
	
}
