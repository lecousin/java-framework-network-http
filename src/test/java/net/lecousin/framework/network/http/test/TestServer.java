package net.lecousin.framework.network.http.test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.buffering.MemoryIO;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPRequest.Method;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientUtil;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;
import net.lecousin.framework.network.http.server.HTTPServerProtocol;
import net.lecousin.framework.network.mime.entity.FormUrlEncodedEntity;
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
				@SuppressWarnings("resource")
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
						response.setHeaderRaw("X-Test", request.getParameter("test"));
					
					IO.Readable body = request.getMIME().getBodyReceivedAsInput();
					if (body != null)
						response.getMIME().setBodyToSend(body);
					
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
		HTTPServerProtocol protocol = new HTTPServerProtocol(new TestProcessor());
		protocol.setReceiveDataTimeout(10000);
		Assert.assertEquals(10000, protocol.getReceiveDataTimeout());
		protocol.getProcessor();
		server.setProtocol(protocol);
		SocketAddress serverAddress = server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100);
		int serverPort = ((InetSocketAddress)serverAddress).getPort();
		// tests
		AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> get;
		get = HTTPClientUtil.GET("http://localhost:" + serverPort + "/test/get?status=200&test=hello", 0);
		check(get, Method.GET, 200, "hello");
		get = HTTPClientUtil.GET("http://localhost:" + serverPort + "/test/get?status=678", 0);
		check(get, Method.GET, 678, null);
		FormUrlEncodedEntity entity = new FormUrlEncodedEntity();
		entity.add("myparam", "myvalue");
		entity.add("Hello", "World!");
		entity.add("test", "this is a test");
		entity.add("test2", "this\nis\tanother+test");
		AsyncWork<Pair<HTTPResponse,IO.Readable.Seekable>, IOException> res = HTTPClientUtil.sendAndReceiveFully(Method.POST, "http://localhost:" + serverPort + "/test/post?status=200", entity);
		res.blockThrow(0);
		FormUrlEncodedEntity entity2 = new FormUrlEncodedEntity();
		entity2.parse(res.getResult().getValue2(), StandardCharsets.UTF_8).blockThrow(0);
		Assert.assertEquals(4, entity2.getParameters().size());
		for (Pair<String, String> p : entity2.getParameters()) {
			if ("myparam".equals(p.getValue1()))
				Assert.assertEquals("myvalue", p.getValue2());
			else if ("Hello".equals(p.getValue1()))
				Assert.assertEquals("World!", p.getValue2());
			else if ("test".equals(p.getValue1()))
				Assert.assertEquals("this is a test", p.getValue2());
			else if ("test2".equals(p.getValue1()))
				Assert.assertEquals("this\nis\tanother+test", p.getValue2());
			else
				throw new AssertionError("Unexpected parameter " + p.getValue1());
		}
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
			Assert.assertEquals("X-Test header", expectedXTest, response.getMIME().getFirstHeaderRawValue("X-Test"));
		else
			Assert.assertFalse(response.getMIME().hasHeader("X-Test"));
	}
	
	@Test(timeout=120000)
	public void test2ConcurrentRequests() throws Exception {
		// launch server
		TCPServer server = new TCPServer();
		server.setProtocol(new HTTPServerProtocol(new TestProcessor()));
		SocketAddress serverAddress = server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100);
		int serverPort = ((InetSocketAddress)serverAddress).getPort();
		// tests
		HTTPClient client = HTTPClient.create(new URI("http://localhost:" + serverPort + "/test/get?status=200&test=hello"));
		HTTPRequest req1 = new HTTPRequest();
		req1.setCommand("GET /test/get?status=200&test=hello HTTP/1.1");
		HTTPRequest req2 = new HTTPRequest();
		req2.setCommand("GET /test/get?status=678 HTTP/1.1");
		client.sendRequest(req1);
		client.sendRequest(req2);
		MemoryIO io1 = new MemoryIO(1024, "test1");
		AsyncWork<HTTPResponse, IOException> headers1 = new AsyncWork<>();
		client.receiveResponse(headers1, io1, 1024).blockThrow(0);
		MemoryIO io2 = new MemoryIO(1024, "test2");
		AsyncWork<HTTPResponse, IOException> headers2 = new AsyncWork<>();
		client.receiveResponse(headers2, io2, 1024).blockThrow(0);
		client.close();
		
		check(new AsyncWork<>(new Pair<>(headers1.getResult(), io1), null), Method.GET, 200, "hello");
		check(new AsyncWork<>(new Pair<>(headers2.getResult(), io2), null), Method.GET, 678, null);

		io1.close();
		io2.close();
		server.close();
	}

}
