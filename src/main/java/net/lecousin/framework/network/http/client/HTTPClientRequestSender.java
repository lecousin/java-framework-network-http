package net.lecousin.framework.network.http.client;

import java.net.URI;

public interface HTTPClientRequestSender {

	/**
	 * Send an HTTP request, with an optional body.<br/>
	 * Filters configured in the HTTPClientConfiguration are first called to modify the request.
	 */
	void send(HTTPClientRequestContext ctx);
	
	/**
	 * Send an HTTP request, with an optional body.<br/>
	 * Filters configured in the HTTPClientConfiguration are first called to modify the request.
	 */
	default HTTPClientResponse send(HTTPClientRequest request) {
		HTTPClientRequestContext ctx = new HTTPClientRequestContext(this, request);
		send(ctx);
		return ctx.getResponse();
	}
	
	void redirectTo(HTTPClientRequestContext ctx, URI targetUri);
	
}
