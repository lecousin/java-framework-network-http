package net.lecousin.framework.network.http.server.processor;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Map;

import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.provider.IOProvider;
import net.lecousin.framework.io.provider.IOProviderFromPathUsingClassloader;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;
import net.lecousin.framework.network.mime.entity.BinaryEntity;

/** Basic processor to return static resources. */
public class StaticProcessor implements HTTPRequestProcessor {

	/** Constructor. */
	public StaticProcessor(String resourcePath, Map<String,String> mimeByExtension) {
		if (!resourcePath.endsWith("/"))
			resourcePath += "/";
		this.resourcePath = resourcePath;
		this.mimeByExtension = mimeByExtension;
		this.provider = new IOProviderFromPathUsingClassloader(getClass().getClassLoader());
	}
	
	private String resourcePath;
	private Map<String,String> mimeByExtension;
	private IOProviderFromPathUsingClassloader provider;
	
	@Override
	public void process(HTTPRequestContext ctx) {
		IO.Readable input = openResource(ctx);
		if (input == null) {
			ctx.getErrorHandler().setError(ctx, HttpURLConnection.HTTP_NOT_FOUND,
				"Resource not found: " + resourcePath + ctx.getRequest().getDecodedPath(), null);
			return;
		}
		ctx.getResponse().setStatus(HttpURLConnection.HTTP_OK);
		BinaryEntity entity = new BinaryEntity(null, ctx.getResponse().getHeaders());
		ctx.getResponse().setEntity(entity);
		entity.setContent(input);
		if (mimeByExtension != null) {
			String extension = ctx.getRequest().getDecodedPath();
			int i = extension.lastIndexOf('/');
			if (i >= 0) extension = extension.substring(i + 1);
			i = extension.lastIndexOf('.');
			if (i >= 0) extension = extension.substring(i + 1);
			else extension = "";
			String mime = mimeByExtension.get(extension);
			if (mime != null)
				entity.setContentType(mime);
		}
		ctx.getResponse().getReady().unblock();
		ctx.getResponse().getSent().onDone(input::closeAsync);
	}
	
	protected IO.Readable openResource(HTTPRequestContext ctx) {
		String path = ctx.getRequest().getDecodedPath();
		if (path.length() > 0) path = path.substring(1); // remove leading slash
		IOProvider.Readable p = provider.get(resourcePath + path);
		try { return p != null ? p.provideIOReadable(Task.getCurrentPriority()) : null; }
		catch (IOException e) {
			return null;
		}
	}
	
}
