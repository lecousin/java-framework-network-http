package net.lecousin.framework.network.http.server;

import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.server.errorhandler.HTTPErrorHandler;
import net.lecousin.framework.network.server.TCPServerClient;

public class HTTPRequestContext {

	private TCPServerClient client;
	private HTTPRequest request;
	private HTTPServerResponse response;
	private HTTPErrorHandler errorHandler;
	
	public HTTPRequestContext(TCPServerClient client, HTTPRequest request, HTTPServerResponse response, HTTPErrorHandler errorHandler) {
		this.client = client;
		this.request = request;
		this.response = response;
		this.errorHandler = errorHandler;
	}

	public HTTPErrorHandler getErrorHandler() {
		return errorHandler;
	}

	public void setErrorHandler(HTTPErrorHandler errorHandler) {
		this.errorHandler = errorHandler;
	}

	public TCPServerClient getClient() {
		return client;
	}

	public HTTPRequest getRequest() {
		return request;
	}

	public HTTPServerResponse getResponse() {
		return response;
	}
	
	
	
}
