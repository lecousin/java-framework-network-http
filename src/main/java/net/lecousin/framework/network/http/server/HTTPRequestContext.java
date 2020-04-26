package net.lecousin.framework.network.http.server;

import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.server.errorhandler.HTTPErrorHandler;
import net.lecousin.framework.network.server.TCPServerClient;

/** Context for processing a request. */
public class HTTPRequestContext {
	
	public static final String REQUEST_ATTRIBUTE_NANOTIME_START = "timing.start";
	public static final String REQUEST_ATTRIBUTE_NANOTIME_HEADERS_RECEIVED = "timing.headers_received";
	public static final String REQUEST_ATTRIBUTE_NANOTIME_BODY_RECEIVED = "timing.body_received";
	public static final String REQUEST_ATTRIBUTE_NANOTIME_PROCESSING_START = "timing.processing_start";
	public static final String REQUEST_ATTRIBUTE_NANOTIME_RESPONSE_READY = "timing.response_ready";
	public static final String REQUEST_ATTRIBUTE_NANOTIME_RESPONSE_SEND_START = "timing.send_start";

	private TCPServerClient client;
	private HTTPRequest request;
	private HTTPServerResponse response;
	private HTTPErrorHandler errorHandler;
	
	/** Constructor. */
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
