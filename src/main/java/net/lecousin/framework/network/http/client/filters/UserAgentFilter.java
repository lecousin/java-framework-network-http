package net.lecousin.framework.network.http.client.filters;

import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientRequestFilter;
import net.lecousin.framework.network.http.client.HTTPClientResponse;

/** Add a User-Agent header. */
public class UserAgentFilter implements HTTPClientRequestFilter {

	/** Constructor. */
	public UserAgentFilter(String userAgent, boolean force) {
		this.userAgent = userAgent;
		this.force = force;
	}
	
	private String userAgent;
	private boolean force;
	
	@Override
	public void filter(HTTPClientRequest request, HTTPClientResponse response) {
		if (force || !request.getHeaders().has(HTTPConstants.Headers.Request.USER_AGENT))
			request.getHeaders().setRawValue(HTTPConstants.Headers.Request.USER_AGENT, userAgent);
	}
	
}
