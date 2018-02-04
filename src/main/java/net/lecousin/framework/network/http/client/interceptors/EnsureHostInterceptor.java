package net.lecousin.framework.network.http.client.interceptors;

import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.client.HTTPRequestInterceptor;

/** Add a Host header if it does not exist. */
public class EnsureHostInterceptor implements HTTPRequestInterceptor {

	@Override
	public void intercept(HTTPRequest request, String hostname, int port) {
		if (!request.getMIME().hasHeader(HTTPRequest.HEADER_HOST)) {
			if (port != 80)
				request.getMIME().setHeaderRaw(HTTPRequest.HEADER_HOST, hostname + ":" + port);
			else
				request.getMIME().setHeaderRaw(HTTPRequest.HEADER_HOST, hostname);
		}
	}
	
}
