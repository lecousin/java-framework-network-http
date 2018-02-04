package net.lecousin.framework.network.http.client.interceptors;

import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.client.HTTPRequestInterceptor;

/** Add a Connection header if it does not exist. */
public class ConnectionInterceptor implements HTTPRequestInterceptor {

	/** Constructor. */
	public ConnectionInterceptor(boolean keepAlive) {
		this.keepAlive = keepAlive;
	}
	
	private boolean keepAlive;
	
	@Override
	public void intercept(HTTPRequest request, String hostname, int port) {
		if (!request.getMIME().hasHeader(HTTPRequest.HEADER_CONNECTION))
			request.getMIME().setHeaderRaw(HTTPRequest.HEADER_CONNECTION, keepAlive ? "Keep-Alive" : "Close");
	}
	
}
