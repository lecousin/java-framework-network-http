package net.lecousin.framework.network.http.test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.buffering.PreBufferedReadable;
import net.lecousin.framework.io.buffering.ReadableToSeekable;
import net.lecousin.framework.io.out2in.OutputToInput;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientResponse;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;
import net.lecousin.framework.network.http.server.HTTPServerResponse;
import net.lecousin.framework.network.http.server.processor.StaticProcessor;
import net.lecousin.framework.network.http1.client.HTTP1ClientConnection;
import net.lecousin.framework.network.http1.server.HTTP1ServerProtocol;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.entity.FormDataEntity;
import net.lecousin.framework.network.mime.entity.FormUrlEncodedEntity;
import net.lecousin.framework.network.mime.entity.MultipartEntity;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.server.protocol.SSLServerProtocol;
import net.lecousin.framework.network.test.AbstractNetworkTest;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.Triple;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.BlockJUnit4ClassRunner;

@RunWith(BlockJUnit4ClassRunner.class)
public class TestServer extends AbstractNetworkTest {

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
	
	@Before
	public void deactivateTraces() {
		deactivateNetworkTraces();
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
		HTTPClientConfiguration config = new HTTPClientConfiguration();
		if (ssl)
			config.setSSLContext(sslTest);
		try (HTTPClient client = new HTTPClient(config)) {
			HTTPClientRequest request = new HTTPClientRequest(serverAddress.getHostString(), serverAddress.getPort(), ssl);
			request.get().setURI("/test/get?status=200&test=hello");
			HTTPClientResponse resp = client.send(request);
			resp.getBodyReceived().blockThrow(0);
			check(resp, 200, "hello");

			request = new HTTPClientRequest(serverAddress.getHostString(), serverAddress.getPort(), ssl);
			request.get().setURI("/test/get?status=678");
			resp = client.send(request);
			resp.getBodyReceived().blockThrow(0);
			check(resp, 678, null);
			
			FormUrlEncodedEntity entity = new FormUrlEncodedEntity();
			entity.add("myparam", "myvalue");
			entity.add("Hello", "World!");
			entity.add("test", "this is a test");
			entity.add("test2", "this\nis\tanother+test");
			request = new HTTPClientRequest(serverAddress.getHostString(), serverAddress.getPort(), ssl);
			request.post("/test/post?status=200", entity);
			resp = client.send(request);
			resp.getBodyReceived().blockThrow(0);
			Assert.assertEquals(FormUrlEncodedEntity.class, resp.getEntity().getClass());
			FormUrlEncodedEntity entity2 = (FormUrlEncodedEntity)resp.getEntity();
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
			request = new HTTPClientRequest(serverAddress.getHostString(), serverAddress.getPort(), ssl);
			request.post("/test/post?status=200", form);
			resp = client.send(request);
			resp.getBodyReceived().blockThrow(0);
			Assert.assertEquals(FormDataEntity.class, resp.getEntity().getClass());
			FormDataEntity form2 = (FormDataEntity)resp.getEntity();
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
		// open connection
		HTTPClientConfiguration config = new HTTPClientConfiguration();
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(TestServer.class);
		Triple<? extends TCPClient, IAsync<IOException>, Boolean> conn = HTTP1ClientConnection.openConnection(
			"localhost", serverPort, false, "/test/get?status=200&test=hello", config, logger);
		TCPClient client = conn.getValue1();
		IAsync<IOException> connection = conn.getValue2();
		// tests
		HTTPClientRequest req1 = new HTTPClientRequest("localhost", serverPort, false).get("/test/get?status=200&test=hello");
		HTTPClientRequest req2 = new HTTPClientRequest("localhost", serverPort, false).get("/test/get?status=602");
		HTTPClientRequest req3 = new HTTPClientRequest("localhost", serverPort, false).get("/test/get?status=603");
		HTTPClientRequest req4 = new HTTPClientRequest("localhost", serverPort, false).get("/test/get?status=604");
		HTTPClientRequest req5 = new HTTPClientRequest("localhost", serverPort, false).get("/test/get?status=605");
		HTTPClientRequest req6 = new HTTPClientRequest("localhost", serverPort, false).get("/test/get?status=606");
		HTTPClientRequest req7 = new HTTPClientRequest("localhost", serverPort, false).get("/test/get?status=607");
		HTTPClientRequest req8 = new HTTPClientRequest("localhost", serverPort, false).get("/test/get?status=608");
		HTTPClientRequest req9 = new HTTPClientRequest("localhost", serverPort, false).get("/test/get?status=609");
		HTTPClientRequest req10 = new HTTPClientRequest("localhost", serverPort, false).get("/test/get?status=610");

		String multiRequest =
			"GET /test/get?status=200&test=hello HTTP/1.1\r\nHost: localhost\r\n\r\n" +
			"GET /test/get?status=602 HTTP/1.1\r\nHost: localhost\r\n\r\n" +
			"GET /test/get?status=603 HTTP/1.1\r\nHost: localhost\r\n\r\n" +
			"GET /test/get?status=604 HTTP/1.1\r\nHost: localhost\r\n\r\n" +
			"GET /test/get?status=605 HTTP/1.1\r\nHost: localhost\r\n\r\n" +
			"GET /test/get?status=606 HTTP/1.1\r\nHost: localhost\r\n\r\n" +
			"GET /test/get?status=607 HTTP/1.1\r\nHost: localhost\r\n\r\n" +
			"GET /test/get?status=608 HTTP/1.1\r\nHost: localhost\r\n\r\n" +
			"GET /test/get?status=609 HTTP/1.1\r\nHost: localhost\r\n\r\n" +
			"GET /test/get?status=610 HTTP/1.1\r\nHost: localhost\r\n\r\n";
		connection.blockThrow(0);
		client.send(ByteBuffer.wrap(multiRequest.getBytes(StandardCharsets.US_ASCII)), 15000);
		
		HTTPClientResponse resp1 = HTTP1ClientConnection.receiveResponse(client, req1, config);
		resp1.getTrailersReceived().blockThrow(0);
		HTTPClientResponse resp2 = HTTP1ClientConnection.receiveResponse(client, req2, config);
		resp2.getTrailersReceived().blockThrow(0);
		HTTPClientResponse resp3 = HTTP1ClientConnection.receiveResponse(client, req3, config);
		resp3.getTrailersReceived().blockThrow(0);
		HTTPClientResponse resp4 = HTTP1ClientConnection.receiveResponse(client, req4, config);
		resp4.getTrailersReceived().blockThrow(0);
		HTTPClientResponse resp5 = HTTP1ClientConnection.receiveResponse(client, req5, config);
		resp5.getTrailersReceived().blockThrow(0);
		HTTPClientResponse resp6 = HTTP1ClientConnection.receiveResponse(client, req6, config);
		resp6.getTrailersReceived().blockThrow(0);
		HTTPClientResponse resp7 = HTTP1ClientConnection.receiveResponse(client, req7, config);
		resp7.getTrailersReceived().blockThrow(0);
		HTTPClientResponse resp8 = HTTP1ClientConnection.receiveResponse(client, req8, config);
		resp8.getTrailersReceived().blockThrow(0);
		HTTPClientResponse resp9 = HTTP1ClientConnection.receiveResponse(client, req9, config);
		resp9.getTrailersReceived().blockThrow(0);
		HTTPClientResponse resp10 = HTTP1ClientConnection.receiveResponse(client, req10, config);
		resp10.getTrailersReceived().blockThrow(0);
		
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
			client.send(ByteBuffer.wrap(buf, i, len).asReadOnlyBuffer(), 10000);
			Thread.sleep(100);
		}
		HTTPClientConfiguration config = new HTTPClientConfiguration();
		HTTPClientResponse resp = HTTP1ClientConnection.receiveResponse(client, new HTTPClientRequest("localhost", serverPort, false), config);
		resp.getTrailersReceived().blockThrow(0);
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
		HTTPClientConfiguration config = new HTTPClientConfiguration();
		HTTPClientResponse response = HTTP1ClientConnection.receiveResponse(client, new HTTPClientRequest("localhost", serverPort, false), config);
		response.getTrailersReceived().blockThrow(0);
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
			ReadableToSeekable io = null;
			try {
				io = new ReadableToSeekable(input, 256);
				ctx.getResponse().setEntity(new BinaryEntity(io));
			} catch (IOException e) {
				e.printStackTrace();
			}
			ctx.getResponse().getReady().unblock();
			if (io != null)
				ctx.getResponse().getSent().onDone(io::closeAsync);
		}
	}
	
	@Test
	public void testRangeRequests() throws Exception {
		try (TCPServer server = new TCPServer()) {
			HTTP1ServerProtocol protocol = new HTTP1ServerProtocol(new RangeProcessor("net/lecousin/framework/network/http/test"));
			protocol.enableRangeRequests();
			server.setProtocol(protocol);
			InetSocketAddress serverAddress = (InetSocketAddress)server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);
	
			// open connection
			HTTPClientConfiguration config = new HTTPClientConfiguration();
			Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(TestServer.class);
			Triple<? extends TCPClient, IAsync<IOException>, Boolean> conn = HTTP1ClientConnection.openConnection(
				"localhost", serverAddress.getPort(), false, "/", config, logger);
			
			try (HTTP1ClientConnection http = new HTTP1ClientConnection(conn.getValue1(), conn.getValue2(), 1, config)) {
				HTTPClientRequest request;
				HTTPClientResponse response;
	
				request = new HTTPClientRequest("localhost", serverAddress.getPort(), false).get();
				request.setDecodedPath("/myresource.txt").setHeader("Range", "bytes=-2");
				response = http.send(request);
				response.getTrailersReceived().blockThrow(0);
				Assert.assertEquals(206, response.getStatusCode());
				String s = IOUtil.readFullyAsStringSync(((BinaryEntity)response.getEntity()).getContent(), StandardCharsets.US_ASCII);
				Assert.assertEquals("ce", s);
				
				request = new HTTPClientRequest("localhost", serverAddress.getPort(), false).get();
				request.setDecodedPath("/myresource.txt").setHeader("Range", "bytes=3-6");
				response = http.send(request);
				response.getTrailersReceived().blockThrow(0);
				Assert.assertEquals(206, response.getStatusCode());
				s = IOUtil.readFullyAsStringSync(((BinaryEntity)response.getEntity()).getContent(), StandardCharsets.US_ASCII);
				Assert.assertEquals("s is", s);
				
				request = new HTTPClientRequest("localhost", serverAddress.getPort(), false).get();
				request.setDecodedPath("/myresource.txt").setHeader("Range", "bytes=12-");
				response = http.send(request);
				response.getTrailersReceived().blockThrow(0);
				Assert.assertEquals(206, response.getStatusCode());
				s = IOUtil.readFullyAsStringSync(((BinaryEntity)response.getEntity()).getContent(), StandardCharsets.US_ASCII);
				Assert.assertEquals("esource", s);
				
				request = new HTTPClientRequest("localhost", serverAddress.getPort(), false).get();
				request.setDecodedPath("/myresource.txt").setHeader("Range", "bytes=3-6,12-");
				response = http.send(request);
				response.getTrailersReceived().blockThrow(0);
				Assert.assertEquals(206, response.getStatusCode());
				Assert.assertEquals(MultipartEntity.class, response.getEntity().getClass());
				MultipartEntity multipart = (MultipartEntity)response.getEntity();
				Assert.assertEquals(2, multipart.getParts().size());
				s = IOUtil.readFullyAsStringSync(((BinaryEntity)multipart.getParts().get(0)).getContent(), StandardCharsets.US_ASCII);
				Assert.assertEquals("s is", s);
				s = IOUtil.readFullyAsStringSync(((BinaryEntity)multipart.getParts().get(1)).getContent(), StandardCharsets.US_ASCII);
				Assert.assertEquals("esource", s);
			}
		}		
	}
	
	@Test
	public void testStaticProcessor() throws Exception {
		try (TCPServer server = new TCPServer()) {
			server.setProtocol(new HTTP1ServerProtocol(new StaticProcessor("net/lecousin/framework/network/http/test", null)));
			InetSocketAddress serverAddress = (InetSocketAddress)server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);

			HTTPClientResponse response = HTTP1ClientConnection.send(new HTTPClientRequest(serverAddress, false).get("/myresource.txt"), 0, BinaryEntity::new, new HTTPClientConfiguration());
			response.getTrailersReceived().blockThrow(0);
			String s = IOUtil.readFullyAsStringSync(((BinaryEntity)response.getEntity()).getContent(), StandardCharsets.US_ASCII);
			Assert.assertEquals("This is my resource", s);
		}
	}
	
	@Test
	public void testPostLargeBody() throws Exception {
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.INFO);
		LCCore.getApplication().getLoggerFactory().getLogger(HTTP1ServerProtocol.class).setLevel(Level.INFO);
		try (TCPServer server = new TCPServer()) {
			server.setProtocol(new HTTP1ServerProtocol(new TestProcessor()));
			InetSocketAddress serverAddress = (InetSocketAddress)server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);
	
			byte[] buf = new byte[10 * 1024 * 1024];
			for (int i = 0; i < buf.length; ++i)
				buf[i] = (byte)(i * 7 % 621);

			HTTPClientResponse response = HTTP1ClientConnection.send(new HTTPClientRequest(serverAddress, false).post("/test/post?status=200", new BinaryEntity(new ByteArrayIO(buf, "test"))), 0, BinaryEntity::new, new HTTPClientConfiguration());
			response.getTrailersReceived().blockThrow(0);
			PreBufferedReadable bio = new PreBufferedReadable(((BinaryEntity)response.getEntity()).getContent(), 8192, Task.Priority.NORMAL, 16384, Task.Priority.NORMAL, 10);
			for (int i = 0; i < buf.length; ++i)
				Assert.assertEquals("Byte at " + i, buf[i] & 0xFF, bio.read());
			Assert.assertEquals(-1, bio.read());
			bio.close();
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
		try (TCPServer server = new TCPServer()) {
			server.setProtocol(new HTTP1ServerProtocol(new TestTrailerTimeProcessor()));
			InetSocketAddress serverAddress = (InetSocketAddress)server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);
	
			HTTPClientResponse response = HTTP1ClientConnection.send(new HTTPClientRequest(serverAddress, false).get("/tutu"), 0, BinaryEntity::new, new HTTPClientConfiguration());
			response.getTrailersReceived().blockThrow(0);
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
		} finally {
			LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.TRACE);
			LCCore.getApplication().getLoggerFactory().getLogger(HTTP1ServerProtocol.class).setLevel(Level.TRACE);
		}
	}
}
