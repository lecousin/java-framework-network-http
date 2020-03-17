package net.lecousin.framework.network.http.client;

/** Allows to modify an HTTP request before it is sent to the server. */
public interface HTTPClientRequestFilter {

	/** Intercept and optionally modify the request before it is sent. */
	void filter(HTTPClientRequest request, HTTPClientResponse response);
	
}
