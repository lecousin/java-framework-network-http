package net.lecousin.framework.network.http.client.interceptors;

import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.client.HTTPRequestInterceptor;

/** Add a Host header if it does not exist. */
public class EnsureHostInterceptor implements HTTPRequestInterceptor {

	@Override
	public void intercept(HTTPRequest request, String hostname, int port) {
		if (!request.getMIME().hasHeader(HTTPConstants.Headers.Request.HOST)) {
			if (port != 80)
				request.getMIME().setHeaderRaw(HTTPConstants.Headers.Request.HOST, hostname + ":" + port);
			else
				request.getMIME().setHeaderRaw(HTTPConstants.Headers.Request.HOST, hostname);
		}
	}
	
}
