package net.lecousin.framework.network.http.server.errorhandler;

import net.lecousin.framework.network.http.server.HTTPRequestContext;

/** Handle an error. This is the opportunity to provide a body containing information about the error. */
public interface HTTPErrorHandler {

	/** Set an error to a request. */
	void setError(HTTPRequestContext ctx, int statusCode, String message, Throwable error);
	
}
