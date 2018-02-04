package net.lecousin.framework.network.http.client.interceptors;

import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.client.HTTPRequestInterceptor;

/** Add a User-Agent header. */
public class UserAgentInterceptor implements HTTPRequestInterceptor {

	/** Constructor. */
	public UserAgentInterceptor(String userAgent, boolean force) {
		this.userAgent = userAgent;
		this.force = force;
	}
	
	private String userAgent;
	private boolean force;
	
	@Override
	public void intercept(HTTPRequest request, String hostname, int port) {
		if (force || !request.getMIME().hasHeader(HTTPRequest.HEADER_USER_AGENT))
			request.getMIME().setHeaderRaw(HTTPRequest.HEADER_USER_AGENT, userAgent);
	}
	
}
