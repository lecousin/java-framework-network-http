package net.lecousin.framework.network.http.test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPRequest.Method;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClientUtil;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;
import net.lecousin.framework.network.http.server.HTTPServerProtocol;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.util.Pair;

import org.junit.Assert;
import org.junit.Test;

public class TestServer extends AbstractHTTPTest {

	private static class TestProcessor implements HTTPRequestProcessor {
		@Override
		public ISynchronizationPoint<?> process(TCPServerClient client, HTTPRequest request, HTTPResponse response) {
			Task<Void, HTTPResponseError> task = new Task.Cpu<Void, HTTPResponseError>("Processing test request", Task.PRIORITY_NORMAL) {
				@Override
				public Void run() throws HTTPResponseError {
					String path = request.getPath();
					if (!path.startsWith("/"))
						throw new HTTPResponseError(500, "Path must start with a slash");
					if (!path.startsWith("/test/"))
						throw new HTTPResponseError(500, "Path must start with /test/");
					String method = path.substring(6);
					String expectedStatus = request.getParameter("status");
					int code;
					try { code = Integer.parseInt(expectedStatus); }
					catch (Exception e) {
						throw new HTTPResponseError(500, "Invalid expected status " + expectedStatus);
					}
					if (!method.equalsIgnoreCase(request.getMethod().name()))
						throw new HTTPResponseError(500, "Method received is " + request.getMethod());
					
					response.setStatus(code, "Test OK");
					if (request.getParameter("test") != null)
						response.setHeader("X-Test", request.getParameter("test"));
					// TODO
					return null;
				}
			};
			task.start();
			return task.getOutput();
		}
	}
	
	@Test(timeout=120000)
	public void testHttpSimpleRequests() throws Exception {
		// launch server
		TCPServer server = new TCPServer();
		server.setProtocol(new HTTPServerProtocol(new TestProcessor()));
		SocketAddress serverAddress = server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100);
		int serverPort = ((InetSocketAddress)serverAddress).getPort();
		// tests
		AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> get;
		get = HTTPClientUtil.GET("http://localhost:" + serverPort + "/test/get?status=200&test=hello", 0);
		check(get, Method.GET, 200, "hello");
		get = HTTPClientUtil.GET("http://localhost:" + serverPort + "/test/get?status=678", 0);
		check(get, Method.GET, 678, null);
		server.close();
	}
	
	private static void check(AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> req, Method method, int status, String expectedXTest) throws Exception {
		req.block(0);
		if (req.hasError()) {
			if ((status / 100) == 2 || !(req.getError() instanceof HTTPResponseError))
				throw req.getError();
			HTTPResponseError err = (HTTPResponseError)req.getError();
			Assert.assertEquals("Status code", status, err.getStatusCode());
			return;
		}
		Pair<HTTPResponse, IO.Readable.Seekable> p = req.getResult();
		HTTPResponse response = p.getValue1();
		if (expectedXTest != null)
			Assert.assertEquals("X-Test header", expectedXTest, response.getMIME().getHeaderSingleValue("X-Test"));
		else
			Assert.assertFalse(response.getMIME().hasHeader("X-Test"));
	}
	
}
