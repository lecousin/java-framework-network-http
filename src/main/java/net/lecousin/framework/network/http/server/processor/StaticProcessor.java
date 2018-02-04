package net.lecousin.framework.network.http.server.processor;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.Threading;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IOFromInputStream;
import net.lecousin.framework.io.provider.IOProviderFromName;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;
import net.lecousin.framework.network.server.TCPServerClient;

/** Basic processor to return static resources. */
public class StaticProcessor implements HTTPRequestProcessor {

	/** Constructor. */
	public StaticProcessor(String resourcePath) {
		if (!resourcePath.endsWith("/"))
			resourcePath += "/";
		this.resourcePath = resourcePath;
	}
	
	private String resourcePath;
	
	@SuppressWarnings("resource")
	@Override
	public ISynchronizationPoint<?> process(TCPServerClient client, HTTPRequest request, HTTPResponse response) {
		String path = request.getPath();
		ClassLoader cl = getClass().getClassLoader();
		IO.Readable input;
		if (cl instanceof IOProviderFromName.Readable) {
			try {
				input = ((IOProviderFromName.Readable)cl).provideReadableIO(resourcePath + path, Task.PRIORITY_NORMAL);
			} catch (IOException e) {
				input = null;
			}
		} else {
			InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath + path);
			if (in == null) input = null;
			else input = new IOFromInputStream(in, resourcePath, Threading.getUnmanagedTaskManager(), Task.PRIORITY_NORMAL);
		}
		if (input == null) {
			response.setStatus(HttpURLConnection.HTTP_NOT_FOUND, "Resource not found: " + resourcePath);
		} else {
			response.setStatus(HttpURLConnection.HTTP_OK);
			response.getMIME().setBodyToSend(input);
		}
		return new SynchronizationPoint<>(true);
	}
	
}
