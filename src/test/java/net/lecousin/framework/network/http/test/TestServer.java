package net.lecousin.framework.network.http.test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.buffering.PreBufferedReadable;
import net.lecousin.framework.io.buffering.ReadableToSeekable;
import net.lecousin.framework.io.out2in.OutputToInput;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;
import net.lecousin.framework.network.http.server.HTTPServerResponse;
import net.lecousin.framework.network.http.server.processor.StaticProcessor;
import net.lecousin.framework.network.http1.server.HTTP1ServerProtocol;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.entity.FormDataEntity;
import net.lecousin.framework.network.mime.entity.FormUrlEncodedEntity;
import net.lecousin.framework.network.mime.entity.MultipartEntity;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.server.protocol.SSLServerProtocol;
import net.lecousin.framework.util.Pair;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.BlockJUnit4ClassRunner;

@RunWith(BlockJUnit4ClassRunner.class)
public class TestServer extends AbstractHTTPTest {

	private static class TestProcessor implements HTTPRequestProcessor {
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
			}
			
			ctx.getResponse().setStatus(code, "Test OK");
			if (ctx.getRequest().getQueryParameter("test") != null)
				ctx.getResponse().setHeader("X-Test", ctx.getRequest().getQueryParameter("test"));
			
			ctx.getResponse().getReady().unblock();
		}
	}
	
	@Test
	public void testHttpSimpleRequests() throws Exception {
		// launch server
		TCPServer server = new TCPServer();
		HTTP1ServerProtocol protocol = new HTTP1ServerProtocol(new TestProcessor());
		protocol.setReceiveDataTimeout(10000);
		Assert.assertEquals(10000, protocol.getReceiveDataTimeout());
		protocol.getProcessor();
		server.setProtocol(protocol);
		InetSocketAddress serverAddress = (InetSocketAddress)server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);

		testSimpleRequests(serverAddress, false);
		server.close();
	}
	
	@Test
	public void testHttpsSimpleRequests() throws Exception {
		// launch server
		TCPServer server = new TCPServer();
		HTTP1ServerProtocol protocol = new HTTP1ServerProtocol(new TestProcessor());
		protocol.setReceiveDataTimeout(10000);
		Assert.assertEquals(10000, protocol.getReceiveDataTimeout());
		protocol.getProcessor();
		server.setProtocol(new SSLServerProtocol(sslTest, protocol));
		InetSocketAddress serverAddress = (InetSocketAddress)server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);

		testSimpleRequests(serverAddress, true);
		server.close();
	}
	
	private static void testSimpleRequests(InetSocketAddress serverAddress, boolean ssl) throws Exception {
		HTTPClientConfiguration config;
		if (ssl) {
			config = new HTTPClientConfiguration(HTTPClientConfiguration.defaultConfiguration);
			config.setSSLContext(sslTest);
		} else
			config = HTTPClientConfiguration.defaultConfiguration;
		try (HTTPClient client = HTTPClient.create(serverAddress, ssl, config)) {
			AsyncSupplier<HTTPResponse, IOException> get;
			
			HTTPResponse resp = client.sendAndReceive(new HTTPRequest().get().setURI("/test/get?status=200&test=hello"), null, null).blockResult(0);
			check(resp, 200, "hello");

			resp = client.sendAndReceive(new HTTPRequest().get().setURI("/test/get?status=678"), null, null).blockResult(0);
			check(resp, 678, null);
			
			FormUrlEncodedEntity entity = new FormUrlEncodedEntity();
			entity.add("myparam", "myvalue");
			entity.add("Hello", "World!");
			entity.add("test", "this is a test");
			entity.add("test2", "this\nis\tanother+test");
			get = client.sendAndReceive(new HTTPRequest().post("/test/post?status=200", entity), null, null);
			get.blockThrow(0);
			Assert.assertEquals(FormUrlEncodedEntity.class, get.getResult().getEntity().getClass());
			FormUrlEncodedEntity entity2 = (FormUrlEncodedEntity)get.getResult().getEntity();
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
			
			FormDataEntity form = new FormDataEntity();
			form.addFile("myfile", "the_filename", new ParameterizedHeaderValue("application/octet-stream"), new ByteArrayIO(new byte[] { 0, 1, 2, 3, 4, 5}, "the_file"));
			form.addField("myfield", "valueofmyfield", StandardCharsets.US_ASCII);
			form.addField("myfield2", "valueofmyfield2", StandardCharsets.US_ASCII);
			form.addFile("f2", "second.bin", new ParameterizedHeaderValue("application/octet-stream"), new ByteArrayIO(new byte[] { 0, 1, 2, 3, 4, 5}, "the_file"));
			form.addFile("f3", "third.bin", new ParameterizedHeaderValue("application/octet-stream"), new ByteArrayIO(new byte[] { 0, 1, 2, 3, 4, 5}, "the_file"));
			get = client.sendAndReceive(new HTTPRequest().post("/test/post?status=200", form), null, null);
			get.blockThrow(0);
			Assert.assertEquals(FormDataEntity.class, get.getResult().getEntity().getClass());
			FormDataEntity form2 = (FormDataEntity)get.getResult().getEntity();
			Assert.assertEquals("valueofmyfield", form2.getFieldValue("myfield"));
			Assert.assertEquals("valueofmyfield2", form2.getFieldValue("myfield2"));
			byte[] buf = new byte[1024];
			int nb = IOUtil.readFully(form2.getFile("myfile").getContent(), ByteBuffer.wrap(buf));
			Assert.assertEquals(6, nb);
			for (int i = 0; i <= 5; ++i)
				Assert.assertEquals(i, buf[i] & 0xFF);
			nb = IOUtil.readFully(form2.getFile("f2").getContent(), ByteBuffer.wrap(buf));
			Assert.assertEquals(6, nb);
			for (int i = 0; i <= 5; ++i)
				Assert.assertEquals(i, buf[i] & 0xFF);
			nb = IOUtil.readFully(form2.getFile("f3").getContent(), ByteBuffer.wrap(buf));
			Assert.assertEquals(6, nb);
			for (int i = 0; i <= 5; ++i)
				Assert.assertEquals(i, buf[i] & 0xFF);
		}
	}
	
	private static void check(HTTPResponse response, int status, String expectedXTest) throws Exception {
		Assert.assertEquals("Status code", status, response.getStatusCode());
		if (expectedXTest != null)
			Assert.assertEquals("X-Test header", expectedXTest, response.getHeaders().getFirstRawValue("X-Test"));
		else
			Assert.assertFalse(response.getHeaders().has("X-Test"));
	}
	
	@Test
	public void testConcurrentRequests() throws Exception {
		// launch server
		TCPServer server = new TCPServer();
		server.setProtocol(new HTTP1ServerProtocol(new TestProcessor()));
		SocketAddress serverAddress = server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);
		int serverPort = ((InetSocketAddress)serverAddress).getPort();
		// tests
		HTTPClient client = HTTPClient.create(new URI("http://localhost:" + serverPort + "/test/get?status=200&test=hello"));
		HTTPRequest req1 = new HTTPRequest().get("/test/get?status=200&test=hello");
		HTTPRequest req2 = new HTTPRequest().get("/test/get?status=602");
		HTTPRequest req3 = new HTTPRequest().get("/test/get?status=603");
		HTTPRequest req4 = new HTTPRequest().get("/test/get?status=604");
		HTTPRequest req5 = new HTTPRequest().get("/test/get?status=605");
		HTTPRequest req6 = new HTTPRequest().get("/test/get?status=606");
		HTTPRequest req7 = new HTTPRequest().get("/test/get?status=607");
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
		client.getTCPClient().send(ByteBuffer.wrap(multiRequest.getBytes(StandardCharsets.US_ASCII)), 15000);
		HTTPResponse resp1 = client.receiveResponse().blockResult(0);
		HTTPResponse resp2 = client.receiveResponse().blockResult(0);
		HTTPResponse resp3 = client.receiveResponse().blockResult(0);
		HTTPResponse resp4 = client.receiveResponse().blockResult(0);
		HTTPResponse resp5 = client.receiveResponse().blockResult(0);
		HTTPResponse resp6 = client.receiveResponse().blockResult(0);
		HTTPResponse resp7 = client.receiveResponse().blockResult(0);
		HTTPResponse resp8 = client.receiveResponse().blockResult(0);
		HTTPResponse resp9 = client.receiveResponse().blockResult(0);
		HTTPResponse resp10 = client.receiveResponse().blockResult(0);
		client.close();
		
		check(resp1, 200, "hello");
		check(resp2, 602, null);
		check(resp3, 603, null);
		check(resp4, 604, null);
		check(resp5, 605, null);
		check(resp6, 606, null);
		check(resp7, 607, null);
		check(resp8, 608, null);
		check(resp9, 609, null);
		check(resp10, 610, null);

		server.close();
	}

	
	@Test
	public void testSendRequestBySmallPackets() throws Exception {
		// launch server
		TCPServer server = new TCPServer();
		server.setProtocol(new HTTP1ServerProtocol(new TestProcessor()));
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
			client.send(ByteBuffer.wrap(buf, i, len), 10000);
			Thread.sleep(100);
		}
		HTTPClient httpClient = new HTTPClient(client, "localhost", serverPort, HTTPClientConfiguration.defaultConfiguration);
		HTTPResponse resp = httpClient.receiveResponse().blockResult(0);
		httpClient.close();
		client.close();
		
		check(resp, 200, "world");

		server.close();
	}
	
	@Test
	public void testInvalidRequestes() throws Exception {
		// launch server
		TCPServer server = new TCPServer();
		server.setProtocol(new HTTP1ServerProtocol(new TestProcessor()));
		SocketAddress serverAddress = server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);
		int serverPort = ((InetSocketAddress)serverAddress).getPort();

		testInvalidRequest("TOTO /titi\r\n\r\n", serverPort, 400);
		testInvalidRequest("GET /titi\r\n\r\n", serverPort, 400);
		testInvalidRequest("GET\r\n\r\n", serverPort, 400);
		testInvalidRequest("GET tutu FTP/10.51\r\n\r\n", serverPort, 400);
		testInvalidRequest("GET /test HTTP/1.1\r\n\tFollowing: nothing\r\n\r\n", serverPort, 400);
		//testInvalidRequest("POST /test/post?status=200 HTTP/1.1\r\nTransfer-Encoding: identity\r\n\r\n", serverPort, 400);
		testInvalidRequest("GET /titi HTTP/1.1\r\n\r\n", serverPort, 500);
		server.close();
	}
	
	private static void testInvalidRequest(String request, int serverPort, int expectedStatus) throws Exception {
		TCPClient client = new TCPClient();
		client.connect(new InetSocketAddress("localhost", serverPort), 10000).blockThrow(0);
		client.send(ByteBuffer.wrap(request.getBytes(StandardCharsets.US_ASCII)), 10000);
		HTTPClient httpClient = new HTTPClient(client, "localhost", serverPort, HTTPClientConfiguration.defaultConfiguration);
		HTTPResponse response = httpClient.receiveResponse().blockResult(0);
		httpClient.close();
		client.close();
		Assert.assertEquals(expectedStatus, response.getStatusCode());
	}
	
	public static class RangeProcessor extends StaticProcessor {
		public RangeProcessor(String path) {
			super(path, null);
		}
		
		@Override
		public void process(HTTPRequestContext ctx) {
			IO.Readable input = openResource(ctx);
			if (input == null) {
				ctx.getErrorHandler().setError(ctx, HttpURLConnection.HTTP_NOT_FOUND, "Not found", null);
				return;
			}
			ctx.getResponse().setStatus(HttpURLConnection.HTTP_OK);
			try {
				ctx.getResponse().setEntity(new BinaryEntity(new ReadableToSeekable(input, 256)));
			} catch (IOException e) {
				e.printStackTrace();
			}
			ctx.getResponse().getReady().unblock();
			ctx.getResponse().getSent().onDone(input::closeAsync);
		}
	}
	
	@Test
	public void testRangeRequests() throws Exception {
		TCPServer server = new TCPServer();
		HTTP1ServerProtocol protocol = new HTTP1ServerProtocol(new RangeProcessor("net/lecousin/framework/network/http/test"));
		protocol.enableRangeRequests();
		server.setProtocol(protocol);
		InetSocketAddress serverAddress = (InetSocketAddress)server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);

		try (HTTPClient client = HTTPClient.create(serverAddress, false)) {
			HTTPResponse response;

			response = client.sendAndReceive(new HTTPRequest().get().setDecodedPath("/myresource.txt").setHeader("Range", "bytes=-2"), null, BinaryEntity::new).blockResult(0);
			Assert.assertEquals(206, response.getStatusCode());
			String s = IOUtil.readFullyAsStringSync(((BinaryEntity)response.getEntity()).getContent(), StandardCharsets.US_ASCII);
			Assert.assertEquals("ce", s);
			
			response = client.sendAndReceive(new HTTPRequest().get().setDecodedPath("/myresource.txt").setHeader("Range", "bytes=3-6"), null, BinaryEntity::new).blockResult(0);
			Assert.assertEquals(206, response.getStatusCode());
			s = IOUtil.readFullyAsStringSync(((BinaryEntity)response.getEntity()).getContent(), StandardCharsets.US_ASCII);
			Assert.assertEquals("s is", s);
			
			response = client.sendAndReceive(new HTTPRequest().get().setDecodedPath("/myresource.txt").setHeader("Range", "bytes=12-"), null, BinaryEntity::new).blockResult(0);
			Assert.assertEquals(206, response.getStatusCode());
			s = IOUtil.readFullyAsStringSync(((BinaryEntity)response.getEntity()).getContent(), StandardCharsets.US_ASCII);
			Assert.assertEquals("esource", s);
			
			response = client.sendAndReceive(new HTTPRequest().get().setDecodedPath("/myresource.txt").setHeader("Range", "bytes=3-6,12-"), null, null).blockResult(0);
			Assert.assertEquals(206, response.getStatusCode());
			Assert.assertEquals(MultipartEntity.class, response.getEntity().getClass());
			MultipartEntity multipart = (MultipartEntity)response.getEntity();
			Assert.assertEquals(2, multipart.getParts().size());
			s = IOUtil.readFullyAsStringSync(((BinaryEntity)multipart.getParts().get(0)).getContent(), StandardCharsets.US_ASCII);
			Assert.assertEquals("s is", s);
			s = IOUtil.readFullyAsStringSync(((BinaryEntity)multipart.getParts().get(1)).getContent(), StandardCharsets.US_ASCII);
			Assert.assertEquals("esource", s);
		}
		
		server.close();
	}
	
	@Test
	public void testStaticProcessor() throws Exception {
		TCPServer server = new TCPServer();
		server.setProtocol(new HTTP1ServerProtocol(new StaticProcessor("net/lecousin/framework/network/http/test", null)));
		InetSocketAddress serverAddress = (InetSocketAddress)server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);
		
		try (HTTPClient client = HTTPClient.create(serverAddress, false)) {
			HTTPResponse response = client.sendAndReceive(new HTTPRequest().get().setDecodedPath("/myresource.txt"), null, BinaryEntity::new).blockResult(0);
			String s = IOUtil.readFullyAsStringSync(((BinaryEntity)response.getEntity()).getContent(), StandardCharsets.US_ASCII);
			Assert.assertEquals("This is my resource", s);
		}
		
		server.close();
	}
	
	@Test
	public void testPostLargeBody() throws Exception {
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.INFO);
		LCCore.getApplication().getLoggerFactory().getLogger(HTTP1ServerProtocol.class).setLevel(Level.INFO);
		try {
			TCPServer server = new TCPServer();
			server.setProtocol(new HTTP1ServerProtocol(new TestProcessor()));
			InetSocketAddress serverAddress = (InetSocketAddress)server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);
	
			byte[] buf = new byte[10 * 1024 * 1024];
			for (int i = 0; i < buf.length; ++i)
				buf[i] = (byte)(i * 7 % 621);
			try (HTTPClient client = HTTPClient.create(serverAddress, false)) {
				HTTPResponse response = client.sendAndReceive(new HTTPRequest().post("/test/post?status=200", new BinaryEntity(new ByteArrayIO(buf, "test"))), null, BinaryEntity::new).blockResult(0);
				PreBufferedReadable bio = new PreBufferedReadable(((BinaryEntity)response.getEntity()).getContent(), 8192, Task.Priority.NORMAL, 16384, Task.Priority.NORMAL, 10);
				for (int i = 0; i < buf.length; ++i)
					Assert.assertEquals("Byte at " + i, buf[i] & 0xFF, bio.read());
				Assert.assertEquals(-1, bio.read());
				bio.close();
			}
			server.close();
		} finally {
			LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.TRACE);
			LCCore.getApplication().getLoggerFactory().getLogger(HTTP1ServerProtocol.class).setLevel(Level.TRACE);
		}
	}
	
	private static class TestTrailerTimeProcessor implements HTTPRequestProcessor {
		
		@Override
		public void process(HTTPRequestContext ctx) {
			HTTPServerResponse response = ctx.getResponse();
			response.setStatus(200);
			BinaryEntity entity = new BinaryEntity(new ByteArrayIO(new byte[123456], "test"));
			response.setEntity(entity);
			response.addHeader("X-Time-Start", ctx.getClient().getAttribute(HTTP1ServerProtocol.REQUEST_START_RECEIVE_NANOTIME_ATTRIBUTE).toString());
			response.addHeader("X-Time-Send", Long.toString(System.nanoTime()));
			response.addTrailerHeader("X-Time-End", () -> Long.toString(System.nanoTime()));
			response.addTrailerHeader("X-Final", () -> "test");
			response.getReady().unblock();
		}
		
	}
	
	@Test
	public void testTrailerTime() throws Exception {
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.INFO);
		LCCore.getApplication().getLoggerFactory().getLogger(HTTP1ServerProtocol.class).setLevel(Level.INFO);
		try {
			TCPServer server = new TCPServer();
			server.setProtocol(new HTTP1ServerProtocol(new TestTrailerTimeProcessor()));
			InetSocketAddress serverAddress = (InetSocketAddress)server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);
	
			try (HTTPClient client = HTTPClient.create(serverAddress, false)) {
				HTTPResponse response = client.sendAndReceive(new HTTPRequest().get().setURI("/tutu"), null, null).blockResult(0);
				Assert.assertTrue(response.getHeaders().has("X-Time-Start"));
				Assert.assertTrue(response.getHeaders().has("X-Time-Send"));
				Assert.assertTrue(response.getHeaders().has("X-Time-End"));
				Assert.assertTrue(response.getHeaders().has("X-Final"));
				long start = Long.parseLong(response.getHeaders().getFirstRawValue("X-Time-Start"));
				long send = Long.parseLong(response.getHeaders().getFirstRawValue("X-Time-Send"));
				long end = Long.parseLong(response.getHeaders().getFirstRawValue("X-Time-End"));
				Assert.assertTrue(send >= start);
				Assert.assertTrue(end >= send);
				Assert.assertEquals("test", response.getHeaders().getFirstRawValue("X-Final"));
			}
			server.close();
		} finally {
			LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.TRACE);
			LCCore.getApplication().getLoggerFactory().getLogger(HTTP1ServerProtocol.class).setLevel(Level.TRACE);
		}
	}
}
