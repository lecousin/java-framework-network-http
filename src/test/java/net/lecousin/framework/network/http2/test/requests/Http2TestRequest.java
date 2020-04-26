package net.lecousin.framework.network.http2.test.requests;

import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.test.requests.TestRequest;

public class Http2TestRequest extends TestRequest {

	public static enum ConnectMethod {
		PRIOR_KNOWLEDGE,
		UPGRADE,
		ALPN
	}
	
	public Http2TestRequest(String name, HTTPClientRequest request, ConnectMethod connectMethod, int maxRedirection, ResponseChecker... checkers) {
		super(name, request, maxRedirection, checkers);
		this.connectMethod = connectMethod;
	}
	
	public ConnectMethod connectMethod;
	
	@Override
	public String toString() {
		return connectMethod + " " + super.toString();
	}
	
}
