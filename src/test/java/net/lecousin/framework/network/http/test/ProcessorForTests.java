package net.lecousin.framework.network.http.test;

import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.out2in.OutputToInput;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;

public class ProcessorForTests implements HTTPRequestProcessor {
	
	@Override
	public void process(HTTPRequestContext ctx) {
		String path = ctx.getRequest().getDecodedPath();
		if (!path.startsWith("/")) {
			ctx.getErrorHandler().setError(ctx, 500, "Path must start with a slash", null);
			return;
		}
		if (!path.startsWith("/test/")) {
			ctx.getErrorHandler().setError(ctx, 500, "Path must start with /test/", null);
			return;
		}
		String method = path.substring(6);
		String expectedStatus = ctx.getRequest().getQueryParameter("status");
		int code;
		try { code = Integer.parseInt(expectedStatus); }
		catch (Exception e) {
			ctx.getErrorHandler().setError(ctx, 500, "Invalid expected status " + expectedStatus, null);
			return;
		}
		if (!method.equalsIgnoreCase(ctx.getRequest().getMethod())) {
			ctx.getErrorHandler().setError(ctx, 500, "Method received is " + ctx.getRequest().getMethod(), null);
			return;
		}
		
		if (ctx.getRequest().isExpectingBody()) {
			BinaryEntity entity = new BinaryEntity(null, ctx.getRequest().getHeaders());
			ctx.getRequest().setEntity(entity);
			OutputToInput o2i = new OutputToInput(new IOInMemoryOrFile(64 * 1024, Task.Priority.NORMAL, "request body"), "request body");
			entity.setContent(o2i);
			entity = new BinaryEntity(null, ctx.getResponse().getHeaders());
			ParameterizedHeaderValue type;
			try { type = ctx.getRequest().getHeaders().getContentType(); }
			catch (Exception e) { type = null; }
			if (type != null)
				entity.addHeader(MimeHeaders.CONTENT_TYPE, type);
			entity.setContent(o2i);
			ctx.getResponse().setEntity(entity);
			ctx.getResponse().getSent().onDone(o2i::closeAsync);
		}
		
		ctx.getResponse().setStatus(code, "Test OK");
		if (ctx.getRequest().getQueryParameter("test") != null)
			ctx.getResponse().setHeader("X-Test", ctx.getRequest().getQueryParameter("test"));
		for (MimeHeader h : ctx.getRequest().getHeaders().getHeaders())
			if (h.getNameLowerCase().startsWith("x-client-"))
				ctx.getResponse().addHeader("X-Server-" + h.getName().substring(9), h.getRawValue());
		
		ctx.getResponse().getReady().unblock();
	}

}
