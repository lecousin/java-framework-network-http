package net.lecousin.framework.network.http.server.processor;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;
import net.lecousin.framework.network.http.server.HTTPServerProtocol;
import net.lecousin.framework.network.server.TCPServerClient;

/** Basic processor for test purposes, that return information about the received request. */
public class DummyProcessor implements HTTPRequestProcessor {

	/** Constructor. */
	public DummyProcessor() {
		this(null);
	}

	/** Constructor. */
	public DummyProcessor(String dummyString) {
		this.dummyString = dummyString;
	}
	
	private String dummyString;
	
	@SuppressWarnings("resource")
	@Override
	public ISynchronizationPoint<?> process(TCPServerClient client, HTTPRequest request, HTTPResponse response) {
		response.setStatus(HttpURLConnection.HTTP_OK);
		response.setContentType("text/html; charset=utf-8");
		StringBuilder s = new StringBuilder();
		s.append("<html><body>Dummy server");
		if (dummyString != null)
			s.append(" (").append(dummyString).append(')');
		s.append(" in ");
		long now = System.nanoTime();
		Long start = (Long)client.getAttribute(HTTPServerProtocol.REQUEST_START_RECEIVE_NANOTIME_ATTRIBUTE);
		s.append(String.format("%.5f",new Double((now - start.longValue()) * 1.d / 1000000000)));
		s.append("s.<br/>We received:<ul>");
		s.append("<li>");
		s.append(request.getMIME().generateHeaders(false).replace("\n", "</li><li>"));
		s.append("</li>");
		s.append("</ul></body></html>");
		response.getMIME().setBodyToSend(new ByteArrayIO(s.toString().getBytes(StandardCharsets.UTF_8), "Dummy response"));
		return new SynchronizationPoint<>(true);
	}
	
}
