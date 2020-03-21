package net.lecousin.framework.network.http.client.filters;

import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientRequestFilter;
import net.lecousin.framework.network.http.client.HTTPClientResponse;
import net.lecousin.framework.network.mime.transfer.ContentDecoderFactory;

public class AcceptEncodingFilter implements HTTPClientRequestFilter {

	@Override
	public void filter(HTTPClientRequest request, HTTPClientResponse response) {
		if (!request.getHeaders().has(HTTPConstants.Headers.Request.ACCEPT_ENCODING)) {
			StringBuilder s = new StringBuilder(128);
			for (String encoding : ContentDecoderFactory.getSupportedEncoding()) {
				if (s.length() > 0) s.append(',');
				s.append(encoding);
			}
			request.setHeader(HTTPConstants.Headers.Request.ACCEPT_ENCODING, s.toString());
		}
	}
	
}
