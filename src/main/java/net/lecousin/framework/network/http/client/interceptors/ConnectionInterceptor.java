package net.lecousin.framework.network.http.client.interceptors;

import net.lecousin.framework.network.http.HTTPConstants;
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
		if (!request.getHeaders().has(HTTPConstants.Headers.Request.CONNECTION))
			request.getHeaders().setRawValue(HTTPConstants.Headers.Request.CONNECTION,
				keepAlive ? HTTPConstants.Headers.Request.CONNECTION_VALUE_KEEP_ALIVE
						  : HTTPConstants.Headers.Request.CONNECTION_VALUE_CLOSE);
	}
	
}
