package net.lecousin.framework.network.http.client.filters;

import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientRequestFilter;
import net.lecousin.framework.network.http.client.HTTPClientResponse;

/** Add a Host header if it does not exist. */
public class EnsureHostFilter implements HTTPClientRequestFilter {

	@Override
	public void filter(HTTPClientRequest request, HTTPClientResponse response) {
		if (request.getProtocolVersion().getMajor() > 1)
			return;
		if (request.getPort() != HTTPConstants.DEFAULT_HTTP_PORT)
			request.getHeaders().setRawValue(HTTPConstants.Headers.Request.HOST, request.getHostname() + ":" + request.getPort());
		else
			request.getHeaders().setRawValue(HTTPConstants.Headers.Request.HOST, request.getHostname());
	}
	
}
