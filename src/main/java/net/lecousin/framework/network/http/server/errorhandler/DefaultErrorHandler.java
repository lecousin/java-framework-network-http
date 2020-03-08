package net.lecousin.framework.network.http.server.errorhandler;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.http.server.HTTPServerResponse;
import net.lecousin.framework.network.mime.entity.EmptyEntity;

/** Default error handler where content-type can be registered. The body of the response will depend on
 * the Accept header from the request.
 */
public class DefaultErrorHandler implements HTTPErrorHandler {

	/** Get the singleton. */
	public static DefaultErrorHandler getInstance() {
		Application app = LCCore.getApplication();
		synchronized (app) {
			DefaultErrorHandler instance = app.getInstance(DefaultErrorHandler.class);
			if (instance == null) {
				instance = new DefaultErrorHandler();
				app.setInstance(DefaultErrorHandler.class, instance);
			}
			return instance;
		}
	}
	
	private DefaultErrorHandler() {
	}
	
	@Override
	public void setError(HTTPRequestContext ctx, int statusCode, String message, Throwable error) {
		HTTPServerResponse response = ctx.getResponse();
		response.setStatus(statusCode, message);
		// TODO include message, according to the Accept header...
		response.setEntity(new EmptyEntity(null, response.getHeaders()));
		if (!response.getReady().isDone())
			response.getReady().unblock();
	}
	
}
