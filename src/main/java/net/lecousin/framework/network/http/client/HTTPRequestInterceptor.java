package net.lecousin.framework.network.http.client;

import net.lecousin.framework.network.http.HTTPRequest;

/** Allows to modify an HTTP request before it is sent to the server. */
public interface HTTPRequestInterceptor {

	/** Intercept and optionally modify the request. */
	public void intercept(HTTPRequest request, String hostname, int port);
	
}
