package net.lecousin.framework.network.http.client.interceptors;

import net.lecousin.framework.network.http.HTTPConstants;
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
		if (force || !request.getHeaders().has(HTTPConstants.Headers.Request.USER_AGENT))
			request.getHeaders().setRawValue(HTTPConstants.Headers.Request.USER_AGENT, userAgent);
	}
	
}
