package net.lecousin.framework.network.http.exception;

/** Exception raised as an IOException when the server returned a status code corresponding to an error. */
public class HTTPError extends Exception {

	private static final long serialVersionUID = 2267959557507886267L;

	/** Constructor. */
	public HTTPError(int code, String message) {
		super(message);
		this.code = code;
	}
	
	protected int code;
	
	public int getStatusCode() {
		return code;
	}
	
}
