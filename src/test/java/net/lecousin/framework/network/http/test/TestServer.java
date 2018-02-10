package net.lecousin.framework.network.http.test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.MemoryIO;
import net.lecousin.framework.io.buffering.ReadableToSeekable;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPRequest.Method;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientUtil;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;
import net.lecousin.framework.network.http.server.HTTPServerProtocol;
import net.lecousin.framework.network.http.server.processor.StaticProcessor;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.entity.FormUrlEncodedEntity;
import net.lecousin.framework.network.mime.entity.MultipartEntity;
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
	public void testConcurrentRequests() throws Exception {
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
		req2.setCommand("GET /test/get?status=602 HTTP/1.1");
		HTTPRequest req3 = new HTTPRequest();
		req3.setCommand("GET /test/get?status=603 HTTP/1.1");
		HTTPRequest req4 = new HTTPRequest();
		req4.setCommand("GET /test/get?status=604 HTTP/1.1");
		HTTPRequest req5 = new HTTPRequest();
		req5.setCommand("GET /test/get?status=605 HTTP/1.1");
		HTTPRequest req6 = new HTTPRequest();
		req6.setCommand("GET /test/get?status=606 HTTP/1.1");
		HTTPRequest req7 = new HTTPRequest();
		req7.setCommand("GET /test/get?status=607 HTTP/1.1");
		client.sendRequest(req1).blockThrow(0);
		client.sendRequest(req2).blockThrow(0);
		client.sendRequest(req3).blockThrow(0);
		client.sendRequest(req4).blockThrow(0);
		client.sendRequest(req5).blockThrow(0);
		client.sendRequest(req6);
		client.sendRequest(req7);
		String multiRequest =
			"GET /test/get?status=608 HTTP/1.1\r\nHost: localhost\r\n\r\n" +
			"GET /test/get?status=609 HTTP/1.1\r\nHost: localhost\r\n\r\n" +
			"GET /test/get?status=610 HTTP/1.1\r\nHost: localhost\r\n\r\n";
		client.getTCPClient().send(ByteBuffer.wrap(multiRequest.getBytes(StandardCharsets.US_ASCII)));
		MemoryIO io1 = new MemoryIO(1024, "test1");
		AsyncWork<HTTPResponse, IOException> headers1 = new AsyncWork<>();
		client.receiveResponse(headers1, io1, 1024).blockThrow(0);
		MemoryIO io2 = new MemoryIO(1024, "test2");
		AsyncWork<HTTPResponse, IOException> headers2 = new AsyncWork<>();
		client.receiveResponse(headers2, io2, 1024).blockThrow(0);
		MemoryIO io3 = new MemoryIO(1024, "test3");
		AsyncWork<HTTPResponse, IOException> headers3 = new AsyncWork<>();
		client.receiveResponse(headers3, io3, 1024).blockThrow(0);
		MemoryIO io4 = new MemoryIO(1024, "test4");
		AsyncWork<HTTPResponse, IOException> headers4 = new AsyncWork<>();
		client.receiveResponse(headers4, io4, 1024).blockThrow(0);
		MemoryIO io5 = new MemoryIO(1024, "test5");
		AsyncWork<HTTPResponse, IOException> headers5 = new AsyncWork<>();
		client.receiveResponse(headers5, io5, 1024).blockThrow(0);
		MemoryIO io6 = new MemoryIO(1024, "test6");
		AsyncWork<HTTPResponse, IOException> headers6 = new AsyncWork<>();
		client.receiveResponse(headers6, io6, 1024).blockThrow(0);
		MemoryIO io7 = new MemoryIO(1024, "test7");
		AsyncWork<HTTPResponse, IOException> headers7 = new AsyncWork<>();
		client.receiveResponse(headers7, io7, 1024).blockThrow(0);
		MemoryIO io8 = new MemoryIO(1024, "test8");
		AsyncWork<HTTPResponse, IOException> headers8 = new AsyncWork<>();
		client.receiveResponse(headers8, io8, 1024).blockThrow(0);
		MemoryIO io9 = new MemoryIO(1024, "test9");
		AsyncWork<HTTPResponse, IOException> headers9 = new AsyncWork<>();
		client.receiveResponse(headers9, io9, 1024).blockThrow(0);
		MemoryIO io10 = new MemoryIO(1024, "test10");
		AsyncWork<HTTPResponse, IOException> headers10 = new AsyncWork<>();
		client.receiveResponse(headers10, io10, 1024).blockThrow(0);
		client.close();
		
		check(new AsyncWork<>(new Pair<>(headers1.getResult(), io1), null), Method.GET, 200, "hello");
		check(new AsyncWork<>(new Pair<>(headers2.getResult(), io2), null), Method.GET, 602, null);
		check(new AsyncWork<>(new Pair<>(headers3.getResult(), io3), null), Method.GET, 603, null);
		check(new AsyncWork<>(new Pair<>(headers4.getResult(), io4), null), Method.GET, 604, null);
		check(new AsyncWork<>(new Pair<>(headers5.getResult(), io5), null), Method.GET, 605, null);
		check(new AsyncWork<>(new Pair<>(headers6.getResult(), io6), null), Method.GET, 606, null);
		check(new AsyncWork<>(new Pair<>(headers7.getResult(), io7), null), Method.GET, 607, null);
		check(new AsyncWork<>(new Pair<>(headers8.getResult(), io8), null), Method.GET, 608, null);
		check(new AsyncWork<>(new Pair<>(headers9.getResult(), io9), null), Method.GET, 609, null);
		check(new AsyncWork<>(new Pair<>(headers10.getResult(), io10), null), Method.GET, 610, null);

		io1.close();
		io2.close();
		io3.close();
		io4.close();
		io5.close();
		io6.close();
		io7.close();
		io8.close();
		io9.close();
		io10.close();
		server.close();
	}

	
	@Test(timeout=120000)
	public void testSendRequestBySmallPackets() throws Exception {
		// launch server
		TCPServer server = new TCPServer();
		server.setProtocol(new HTTPServerProtocol(new TestProcessor()));
		SocketAddress serverAddress = server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100);
		int serverPort = ((InetSocketAddress)serverAddress).getPort();

		TCPClient client = new TCPClient();
		client.connect(new InetSocketAddress("localhost", serverPort), 10000).blockThrow(0);
		String req =
			"GET /test/get?status=200&test=world HTTP/1.1\r\n" +
			"Host: localhost:" + serverPort + "\r\n" +
			"X-Test: hello world\r\n" +
			"\r\n";
		byte[] buf = req.getBytes(StandardCharsets.US_ASCII);
		for (int i = 0; i < buf.length; i += 10) {
			int len = 10;
			if (i + len > buf.length) len = buf.length - i;
			client.send(ByteBuffer.wrap(buf, i, len));
			Thread.sleep(100);
		}
		HTTPClient httpClient = new HTTPClient(client, "localhost", serverPort, HTTPClientConfiguration.defaultConfiguration);
		MemoryIO io = new MemoryIO(1024, "test1");
		AsyncWork<HTTPResponse, IOException> headers = new AsyncWork<>();
		httpClient.receiveResponse(headers, io, 1024).blockThrow(0);
		httpClient.close();
		client.close();
		
		check(new AsyncWork<>(new Pair<>(headers.getResult(), io), null), Method.GET, 200, "world");

		io.close();
		server.close();
	}
	
	public static class RangeProcessor extends StaticProcessor {
		public RangeProcessor(String path) {
			super(path);
		}
		
		@Override
		public ISynchronizationPoint<?> process(TCPServerClient client, HTTPRequest request, HTTPResponse response) {
			ISynchronizationPoint<?> res = super.process(client, request, response);
			IO.Readable io = response.getMIME().getBodyToSend();
			if (io != null)
				try { response.getMIME().setBodyToSend(new ReadableToSeekable(io, 256)); }
				catch (IOException e) {
					e.printStackTrace(System.err);
				}
			return res;
		}
	}
	
	@Test(timeout=120000)
	public void testRangeRequests() throws Exception {
		TCPServer server = new TCPServer();
		HTTPServerProtocol protocol = new HTTPServerProtocol(new RangeProcessor("net/lecousin/framework/network/http/test"));
		protocol.enableRangeRequests();
		server.setProtocol(protocol);
		SocketAddress serverAddress = server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100);
		int serverPort = ((InetSocketAddress)serverAddress).getPort();
		
		MimeMessage message = new MimeMessage();
		message.setHeaderRaw("Range", "bytes=-2");
		Pair<HTTPResponse, IO.Readable.Seekable> p = HTTPClientUtil.sendAndReceiveFully(Method.GET, "http://localhost:" + serverPort + "/myresource.txt", message).blockResult(0);
		Assert.assertEquals(206, p.getValue1().getStatusCode());
		String s = IOUtil.readFullyAsStringSync(p.getValue2(), StandardCharsets.US_ASCII);
		Assert.assertEquals("ce", s);
		
		message = new MimeMessage();
		message.setHeaderRaw("Range", "bytes=3-6");
		p = HTTPClientUtil.sendAndReceiveFully(Method.GET, "http://localhost:" + serverPort + "/myresource.txt", message).blockResult(0);
		Assert.assertEquals(206, p.getValue1().getStatusCode());
		s = IOUtil.readFullyAsStringSync(p.getValue2(), StandardCharsets.US_ASCII);
		Assert.assertEquals("s is", s);
		
		message = new MimeMessage();
		message.setHeaderRaw("Range", "bytes=12-");
		p = HTTPClientUtil.sendAndReceiveFully(Method.GET, "http://localhost:" + serverPort + "/myresource.txt", message).blockResult(0);
		Assert.assertEquals(206, p.getValue1().getStatusCode());
		s = IOUtil.readFullyAsStringSync(p.getValue2(), StandardCharsets.US_ASCII);
		Assert.assertEquals("esource", s);
		
		message = new MimeMessage();
		message.setHeaderRaw("Range", "bytes=3-6,12-");
		p = HTTPClientUtil.sendAndReceiveFully(Method.GET, "http://localhost:" + serverPort + "/myresource.txt", message).blockResult(0);
		Assert.assertEquals(206, p.getValue1().getStatusCode());
		MultipartEntity multipart = MultipartEntity.from(p.getValue1().getMIME(), true).blockResult(0);
		Assert.assertEquals(2, multipart.getParts().size());
		s = IOUtil.readFullyAsStringSync(multipart.getParts().get(0).getBodyReceivedAsInput(), StandardCharsets.US_ASCII);
		Assert.assertEquals("s is", s);
		s = IOUtil.readFullyAsStringSync(multipart.getParts().get(1).getBodyReceivedAsInput(), StandardCharsets.US_ASCII);
		Assert.assertEquals("esource", s);
		
		server.close();
	}
	
	@Test(timeout=60000)
	public void testStaticProcessor() throws Exception {
		TCPServer server = new TCPServer();
		server.setProtocol(new HTTPServerProtocol(new StaticProcessor("net/lecousin/framework/network/http/test")));
		SocketAddress serverAddress = server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100);
		int serverPort = ((InetSocketAddress)serverAddress).getPort();

		AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> get;
		get = HTTPClientUtil.GET("http://localhost:" + serverPort + "/myresource.txt", 0);
		String s = IOUtil.readFullyAsStringSync(get.blockResult(0).getValue2(), StandardCharsets.US_ASCII);
		Assert.assertEquals("This is my resource", s);
		
		server.close();
	}
	
}
