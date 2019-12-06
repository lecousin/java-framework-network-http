package net.lecousin.framework.network.http.server.processor;

import java.io.IOException;
import java.net.HttpURLConnection;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.provider.IOProvider;
import net.lecousin.framework.io.provider.IOProviderFromPathUsingClassloader;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;
import net.lecousin.framework.network.http.server.HTTPServerResponse;
import net.lecousin.framework.network.server.TCPServerClient;

/** Basic processor to return static resources. */
public class StaticProcessor implements HTTPRequestProcessor {

	/** Constructor. */
	public StaticProcessor(String resourcePath) {
		if (!resourcePath.endsWith("/"))
			resourcePath += "/";
		this.resourcePath = resourcePath;
		this.provider = new IOProviderFromPathUsingClassloader(getClass().getClassLoader());
	}
	
	private String resourcePath;
	private IOProviderFromPathUsingClassloader provider;
	
	@Override
	public IAsync<?> process(TCPServerClient client, HTTPRequest request, HTTPServerResponse response) {
		String path = request.getPath();
		if (path.length() > 0) path = path.substring(1); // remove leading slash
		IOProvider.Readable p = provider.get(resourcePath + path);
		IO.Readable input;
		try { input = p != null ? p.provideIOReadable(Task.PRIORITY_NORMAL) : null; }
		catch (IOException e) {
			input = null;
		}
		if (input == null) {
			response.setStatus(HttpURLConnection.HTTP_NOT_FOUND, "Resource not found: " + resourcePath);
		} else {
			response.setStatus(HttpURLConnection.HTTP_OK);
			response.getMIME().setBodyToSend(input);
		}
		return new Async<>(true);
	}
	
}
