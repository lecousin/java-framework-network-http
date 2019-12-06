package net.lecousin.framework.network.http.exception;

/** Exception raised when a server operation failed, to attach the status code to respond to the client. */
public class HTTPError extends Exception {

	private static final long serialVersionUID = 2267959557507886267L;

	/** Constructor. */
	public HTTPError(int code, String message) {
		super(message);
		this.code = code;
	}
	
	private final int code;
	
	public int getStatusCode() {
		return code;
	}
	
}
