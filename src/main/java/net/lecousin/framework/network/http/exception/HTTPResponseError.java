package net.lecousin.framework.network.http.exception;

import java.io.IOException;

/** Exception raised as an IOException when the server returned a status code corresponding to an error. */
public class HTTPResponseError extends IOException {

	private static final long serialVersionUID = 2267959557507886266L;

	/** Constructor. */
	public HTTPResponseError(int statusCode, String statusMessage) {
		super("Server returned an error " + statusCode + ": " + statusMessage);
		this.statusCode = statusCode;
	}
	
	protected int statusCode;
	
	public int getStatusCode() {
		return statusCode;
	}
	
}
