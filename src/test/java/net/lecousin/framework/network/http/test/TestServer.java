package net.lecousin.framework.network.http.test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.MemoryIO;
import net.lecousin.framework.io.buffering.PreBufferedReadable;
import net.lecousin.framework.io.buffering.ReadableToSeekable;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPRequest.Method;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;
import net.lecousin.framework.network.http.server.HTTPServerProtocol;
import net.lecousin.framework.network.http.server.HTTPServerResponse;
import net.lecousin.framework.network.http.server.processor.StaticProcessor;
import net.lecousin.framework.network.mime.MimeHeader;
import net.lecousin.framework.network.mime.entity.FormUrlEncodedEntity;
import net.lecousin.framework.network.mime.entity.MultipartEntity;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.util.Pair;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.BlockJUnit4ClassRunner;

@RunWith(BlockJUnit4ClassRunner.class)
public class TestServer extends AbstractHTTPTest {

	private static class TestProcessor implements HTTPRequestProcessor {
		@Override
		public IAsync<?> process(TCPServerClient client, HTTPRequest request, HTTPServerResponse response) {
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
	
	@Test
	public void testHttpSimpleRequests() throws Exception {
		// launch server
		TCPServer server = new TCPServer();
		HTTPServerProtocol protocol = new HTTPServerProtocol(new TestProcessor());
		protocol.setReceiveDataTimeout(10000);
		Assert.assertEquals(10000, protocol.getReceiveDataTimeout());
		protocol.getProcessor();
		server.setProtocol(protocol);
		InetSocketAddress serverAddress = (InetSocketAddress)server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);
		
		try (HTTPClient client = HTTPClient.create(serverAddress, false)) {
			AsyncSupplier<HTTPResponse, IOException> get;
			
			get = client.sendAndReceive(new HTTPRequest(Method.GET).setPathAndQueryString("/test/get?status=200&test=hello"), true, false, 0);
			check(get, 200, "hello");

			get = client.sendAndReceive(new HTTPRequest(Method.GET).setPathAndQueryString("/test/get?status=678"), false, false, 0);
			check(get, 678, null);
			
			FormUrlEncodedEntity entity = new FormUrlEncodedEntity();
			entity.add("myparam", "myvalue");
			entity.add("Hello", "World!");
			entity.add("test", "this is a test");
			entity.add("test2", "this\nis\tanother+test");
			get = client.sendAndReceive(new HTTPRequest().setPathAndQueryString("/test/post?status=200").post(entity), true, true, 0);
			get.blockThrow(0);
			FormUrlEncodedEntity entity2 = new FormUrlEncodedEntity();
			entity2.parse(get.getResult().getMIME().getBodyReceivedAsInput(), StandardCharsets.UTF_8).blockThrow(0);
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
		}
		server.close();
	}
	
	private static void check(AsyncSupplier<HTTPResponse, IOException> req, int status, String expectedXTest) throws Exception {
		req.block(0);
		if (req.hasError()) {
			if ((status / 100) == 2 || !(req.getError() instanceof HTTPResponseError))
				throw req.getError();
			HTTPResponseError err = (HTTPResponseError)req.getError();
			Assert.assertEquals("Status code", status, err.getStatusCode());
			return;
		}
		HTTPResponse response = req.getResult();
		if (expectedXTest != null)
			Assert.assertEquals("X-Test header", expectedXTest, response.getMIME().getFirstHeaderRawValue("X-Test"));
		else
			Assert.assertFalse(response.getMIME().hasHeader("X-Test"));
	}
	
	@Test
	public void testConcurrentRequests() throws Exception {
		// launch server
		TCPServer server = new TCPServer();
		server.setProtocol(new HTTPServerProtocol(new TestProcessor()));
		SocketAddress serverAddress = server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);
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
		AsyncSupplier<HTTPResponse, IOException> headers1 = new AsyncSupplier<>();
		client.receiveResponse(headers1, io1, 1024).blockThrow(0);
		MemoryIO io2 = new MemoryIO(1024, "test2");
		AsyncSupplier<HTTPResponse, IOException> headers2 = new AsyncSupplier<>();
		client.receiveResponse(headers2, io2, 1024).blockThrow(0);
		MemoryIO io3 = new MemoryIO(1024, "test3");
		AsyncSupplier<HTTPResponse, IOException> headers3 = new AsyncSupplier<>();
		client.receiveResponse(headers3, io3, 1024).blockThrow(0);
		MemoryIO io4 = new MemoryIO(1024, "test4");
		AsyncSupplier<HTTPResponse, IOException> headers4 = new AsyncSupplier<>();
		client.receiveResponse(headers4, io4, 1024).blockThrow(0);
		MemoryIO io5 = new MemoryIO(1024, "test5");
		AsyncSupplier<HTTPResponse, IOException> headers5 = new AsyncSupplier<>();
		client.receiveResponse(headers5, io5, 1024).blockThrow(0);
		MemoryIO io6 = new MemoryIO(1024, "test6");
		AsyncSupplier<HTTPResponse, IOException> headers6 = new AsyncSupplier<>();
		client.receiveResponse(headers6, io6, 1024).blockThrow(0);
		MemoryIO io7 = new MemoryIO(1024, "test7");
		AsyncSupplier<HTTPResponse, IOException> headers7 = new AsyncSupplier<>();
		client.receiveResponse(headers7, io7, 1024).blockThrow(0);
		MemoryIO io8 = new MemoryIO(1024, "test8");
		AsyncSupplier<HTTPResponse, IOException> headers8 = new AsyncSupplier<>();
		client.receiveResponse(headers8, io8, 1024).blockThrow(0);
		MemoryIO io9 = new MemoryIO(1024, "test9");
		AsyncSupplier<HTTPResponse, IOException> headers9 = new AsyncSupplier<>();
		client.receiveResponse(headers9, io9, 1024).blockThrow(0);
		MemoryIO io10 = new MemoryIO(1024, "test10");
		AsyncSupplier<HTTPResponse, IOException> headers10 = new AsyncSupplier<>();
		client.receiveResponse(headers10, io10, 1024).blockThrow(0);
		client.close();
		
		check(headers1, 200, "hello");
		check(headers2, 602, null);
		check(headers3, 603, null);
		check(headers4, 604, null);
		check(headers5, 605, null);
		check(headers6, 606, null);
		check(headers7, 607, null);
		check(headers8, 608, null);
		check(headers9, 609, null);
		check(headers10, 610, null);

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

	
	@Test
	public void testSendRequestBySmallPackets() throws Exception {
		// launch server
		TCPServer server = new TCPServer();
		server.setProtocol(new HTTPServerProtocol(new TestProcessor()));
		SocketAddress serverAddress = server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);
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
		AsyncSupplier<HTTPResponse, IOException> headers = new AsyncSupplier<>();
		httpClient.receiveResponse(headers, io, 1024).blockThrow(0);
		httpClient.close();
		client.close();
		
		check(headers, 200, "world");

		io.close();
		server.close();
	}
	
	@Test
	public void testInvalidRequestes() throws Exception {
		// launch server
		TCPServer server = new TCPServer();
		server.setProtocol(new HTTPServerProtocol(new TestProcessor()));
		SocketAddress serverAddress = server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);
		int serverPort = ((InetSocketAddress)serverAddress).getPort();

		testInvalidRequest("TOTO /titi\r\n\r\n", serverPort, 400);
		testInvalidRequest("GET /titi\r\n\r\n", serverPort, 400);
		testInvalidRequest("GET\r\n\r\n", serverPort, 400);
		testInvalidRequest("GET tutu FTP/10.51\r\n\r\n", serverPort, 400);
		server.close();
	}
	
	private static void testInvalidRequest(String request, int serverPort, int expectedStatus) throws Exception {
		TCPClient client = new TCPClient();
		client.connect(new InetSocketAddress("localhost", serverPort), 10000).blockThrow(0);
		client.send(ByteBuffer.wrap(request.getBytes(StandardCharsets.US_ASCII)));
		HTTPClient httpClient = new HTTPClient(client, "localhost", serverPort, HTTPClientConfiguration.defaultConfiguration);
		MemoryIO io = new MemoryIO(1024, "test1");
		AsyncSupplier<HTTPResponse, IOException> headers = new AsyncSupplier<>();
		httpClient.receiveResponse(headers, io, 1024).blockThrow(0);
		httpClient.close();
		client.close();
		Assert.assertEquals(expectedStatus, headers.getResult().getStatusCode());
	}
	
	public static class RangeProcessor extends StaticProcessor {
		public RangeProcessor(String path) {
			super(path);
		}
		
		@Override
		public IAsync<?> process(TCPServerClient client, HTTPRequest request, HTTPServerResponse response) {
			IAsync<?> res = super.process(client, request, response);
			IO.Readable io = response.getMIME().getBodyToSend();
			if (io != null)
				try { response.getMIME().setBodyToSend(new ReadableToSeekable(io, 256)); }
				catch (IOException e) {
					e.printStackTrace(System.err);
				}
			return res;
		}
	}
	
	@Test
	public void testRangeRequests() throws Exception {
		TCPServer server = new TCPServer();
		HTTPServerProtocol protocol = new HTTPServerProtocol(new RangeProcessor("net/lecousin/framework/network/http/test"));
		protocol.enableRangeRequests();
		server.setProtocol(protocol);
		InetSocketAddress serverAddress = (InetSocketAddress)server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);

		try (HTTPClient client = HTTPClient.create(serverAddress, false)) {
			HTTPResponse response;

			response = client.sendAndReceive(new HTTPRequest(Method.GET).setPath("/myresource.txt").setHeaders(new MimeHeader("Range", "bytes=-2")), true, true, 0).blockResult(0);
			Assert.assertEquals(206, response.getStatusCode());
			String s = IOUtil.readFullyAsStringSync(response.getMIME().getBodyReceivedAsInput(), StandardCharsets.US_ASCII);
			Assert.assertEquals("ce", s);
			
			response = client.sendAndReceive(new HTTPRequest(Method.GET).setPath("/myresource.txt").setHeaders(new MimeHeader("Range", "bytes=3-6")), true, true, 0).blockResult(0);
			Assert.assertEquals(206, response.getStatusCode());
			s = IOUtil.readFullyAsStringSync(response.getMIME().getBodyReceivedAsInput(), StandardCharsets.US_ASCII);
			Assert.assertEquals("s is", s);
			
			response = client.sendAndReceive(new HTTPRequest(Method.GET).setPath("/myresource.txt").setHeaders(new MimeHeader("Range", "bytes=12-")), true, true, 0).blockResult(0);
			Assert.assertEquals(206, response.getStatusCode());
			s = IOUtil.readFullyAsStringSync(response.getMIME().getBodyReceivedAsInput(), StandardCharsets.US_ASCII);
			Assert.assertEquals("esource", s);
			
			response = client.sendAndReceive(new HTTPRequest(Method.GET).setPath("/myresource.txt").setHeaders(new MimeHeader("Range", "bytes=3-6,12-")), true, true, 0).blockResult(0);
			Assert.assertEquals(206, response.getStatusCode());
			MultipartEntity multipart = MultipartEntity.from(response.getMIME(), true).blockResult(0);
			Assert.assertEquals(2, multipart.getParts().size());
			s = IOUtil.readFullyAsStringSync(multipart.getParts().get(0).getBodyReceivedAsInput(), StandardCharsets.US_ASCII);
			Assert.assertEquals("s is", s);
			s = IOUtil.readFullyAsStringSync(multipart.getParts().get(1).getBodyReceivedAsInput(), StandardCharsets.US_ASCII);
			Assert.assertEquals("esource", s);
		}
		
		server.close();
	}
	
	@Test
	public void testStaticProcessor() throws Exception {
		TCPServer server = new TCPServer();
		server.setProtocol(new HTTPServerProtocol(new StaticProcessor("net/lecousin/framework/network/http/test")));
		InetSocketAddress serverAddress = (InetSocketAddress)server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);
		
		try (HTTPClient client = HTTPClient.create(serverAddress, false)) {
			HTTPResponse response = client.sendAndReceive(new HTTPRequest(Method.GET).setPath("/myresource.txt"), true, false, 0).blockResult(0);
			String s = IOUtil.readFullyAsStringSync(response.getMIME().getBodyReceivedAsInput(), StandardCharsets.US_ASCII);
			Assert.assertEquals("This is my resource", s);
		}
		
		server.close();
	}
	
	@Test
	public void testPostLargeBody() throws Exception {
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.INFO);
		LCCore.getApplication().getLoggerFactory().getLogger(HTTPServerProtocol.class).setLevel(Level.INFO);
		try {
			TCPServer server = new TCPServer();
			server.setProtocol(new HTTPServerProtocol(new TestProcessor()));
			InetSocketAddress serverAddress = (InetSocketAddress)server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);
	
			byte[] buf = new byte[10 * 1024 * 1024];
			for (int i = 0; i < buf.length; ++i)
				buf[i] = (byte)(i * 7 % 621);
			try (HTTPClient client = HTTPClient.create(serverAddress, false)) {
				HTTPResponse response = client.sendAndReceive(new HTTPRequest().setPathAndQueryString("/test/post?status=200").post(new ByteArrayIO(buf, "test")), true, false, 0).blockResult(0);
				PreBufferedReadable bio = new PreBufferedReadable(response.getMIME().getBodyReceivedAsInput(), 8192, Task.PRIORITY_NORMAL, 16384, Task.PRIORITY_NORMAL, 10);
				for (int i = 0; i < buf.length; ++i)
					Assert.assertEquals("Byte at " + i, buf[i] & 0xFF, bio.read());
				Assert.assertEquals(-1, bio.read());
				bio.close();
			}
			server.close();
		} finally {
			LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.TRACE);
			LCCore.getApplication().getLoggerFactory().getLogger(HTTPServerProtocol.class).setLevel(Level.TRACE);
		}
	}
	
}
