package net.lecousin.framework.network.http.exception;

import java.io.IOException;

import net.lecousin.framework.network.http.HTTPResponse;

/** Exception raised as an IOException when the server returned a status code corresponding to an error. */
public class HTTPResponseError extends IOException {

	private static final long serialVersionUID = 2267959557507886266L;

	/** Constructor. */
	public HTTPResponseError(int statusCode, String statusMessage) {
		super("Server returned an error " + statusCode + ": " + statusMessage);
		this.statusCode = statusCode;
	}
	
	/** Constructor. */
	public HTTPResponseError(HTTPResponse response) {
		this(response.getStatusCode(), response.getStatusMessage());
	}
	
	private final int statusCode;
	
	public int getStatusCode() {
		return statusCode;
	}
	
}
