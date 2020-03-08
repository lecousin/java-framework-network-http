package net.lecousin.framework.network.http.server.errorhandler;

import net.lecousin.framework.network.http.server.HTTPRequestContext;

public interface HTTPErrorHandler {

	void setError(HTTPRequestContext ctx, int statusCode, String message, Throwable error);
	
}
