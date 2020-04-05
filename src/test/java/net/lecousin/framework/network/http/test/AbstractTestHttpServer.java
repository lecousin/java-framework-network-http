package net.lecousin.framework.network.http.test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.PreBufferedReadable;
import net.lecousin.framework.io.buffering.ReadableToSeekable;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http.client.HTTPClientRequestSender;
import net.lecousin.framework.network.http.client.HTTPClientResponse;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;
import net.lecousin.framework.network.http.server.HTTPServerResponse;
import net.lecousin.framework.network.http.server.processor.StaticProcessor;
import net.lecousin.framework.network.http1.server.HTTP1ServerProtocol;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.entity.FormDataEntity;
import net.lecousin.framework.network.mime.entity.FormUrlEncodedEntity;
import net.lecousin.framework.network.mime.entity.MultipartEntity;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.server.protocol.SSLServerProtocol;
import net.lecousin.framework.network.server.protocol.ServerProtocol;
import net.lecousin.framework.network.test.AbstractNetworkTest;
import net.lecousin.framework.util.Pair;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public abstract class AbstractTestHttpServer extends AbstractNetworkTest {

	@Parameters(name = "ssl = {0}")
	public static Collection<Object[]> parameters() {
		return Arrays.asList(new Object[] { Boolean.FALSE }, new Object[] { Boolean.TRUE });
	}
	
	protected boolean useSSL;
	protected TCPServer server;
	protected InetSocketAddress serverAddress;
	protected HTTPClientConfiguration clientConfig;
	
	public AbstractTestHttpServer(boolean useSSL) {
		this.useSSL = useSSL;
	}
	
	protected abstract ServerProtocol createProtocol(HTTPRequestProcessor processor);
	
	protected abstract HTTPClientRequestSender createClient() throws Exception;
	
	protected abstract void stopLogging();
	
	protected abstract void resumeLogging();
	
	@Before
	public void deactivateTraces() {
		deactivateNetworkTraces();
	}
	
	protected void startServer(HTTPRequestProcessor processor) throws Exception {
		server = new TCPServer();
		ServerProtocol protocol = createProtocol(processor);
		if (useSSL) protocol = new SSLServerProtocol(sslTest, protocol);
		server.setProtocol(protocol);
		serverAddress = (InetSocketAddress)server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);
		clientConfig = new HTTPClientConfiguration();
		if (useSSL) clientConfig.setSSLContext(sslTest);
	}
	
	@After
	public void closeServer() {
		server.close();
	}
	
	@Test
	public void testGetWithParameter() throws Exception {
		startServer(new ProcessorForTests());
		try (HTTPClientRequestSender client = createClient()) {
			HTTPClientRequest request = new HTTPClientRequest(serverAddress.getHostString(), serverAddress.getPort(), useSSL);
			request.get().setURI("/test/get?status=200&test=hello");
			HTTPClientResponse resp = client.send(request);
			resp.getBodyReceived().blockThrow(0);
			check(resp, 200, "hello");
		}
	}
	
	@Test
	public void testGetStatus() throws Exception {
		startServer(new ProcessorForTests());
		try (HTTPClientRequestSender client = createClient()) {
			HTTPClientRequest request = new HTTPClientRequest(serverAddress.getHostString(), serverAddress.getPort(), useSSL);
			request = new HTTPClientRequest(serverAddress.getHostString(), serverAddress.getPort(), useSSL);
			request.get().setURI("/test/get?status=678");
			HTTPClientResponse resp = client.send(request);
			resp.getBodyReceived().blockThrow(0);
			check(resp, 678, null);
		}
	}
	
	@Test
	public void testManyHeaders() throws Exception {
		stopLogging();
		startServer(new ProcessorForTests());
		try (HTTPClientRequestSender client = createClient()) {
			HTTPClientRequest request = new HTTPClientRequest(serverAddress.getHostString(), serverAddress.getPort(), useSSL);
			request = new HTTPClientRequest(serverAddress.getHostString(), serverAddress.getPort(), useSSL);
			request.get().setURI("/test/get?status=201");
			for (int i = 0; i < 1000; ++i)
				request.addHeader("X-Client-" + i, "Value" + i);
			HTTPClientResponse resp = client.send(request);
			resp.getBodyReceived().blockThrow(0);
			check(resp, 201, null);
			for (int i = 0; i < 1000; ++i) {
				List<MimeHeader> list = resp.getHeaders().getList("X-Server-" + i);
				Assert.assertEquals("Header " + i, 1, list.size());
				Assert.assertEquals("Header " + i, "Value" + i, list.get(0).getRawValue());
			}
		}
		resumeLogging();
	}
	
	@Test
	public void testFormUrlEncodedEntity() throws Exception {
		startServer(new ProcessorForTests());
		try (HTTPClientRequestSender client = createClient()) {
			HTTPClientRequest request = new HTTPClientRequest(serverAddress.getHostString(), serverAddress.getPort(), useSSL);
			FormUrlEncodedEntity entity = new FormUrlEncodedEntity();
			entity.add("myparam", "myvalue");
			entity.add("Hello", "World!");
			entity.add("test", "this is a test");
			entity.add("test2", "this\nis\tanother+test");
			request = new HTTPClientRequest(serverAddress.getHostString(), serverAddress.getPort(), useSSL);
			request.post("/test/post?status=200", entity);
			HTTPClientResponse resp = client.send(request);
			resp.getBodyReceived().blockThrow(0);
			Assert.assertEquals(200, resp.getStatusCode());
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
		}
	}

	@Test
	public void testFormDataEntity() throws Exception {
		startServer(new ProcessorForTests());
		try (HTTPClientRequestSender client = createClient()) {
			HTTPClientRequest request = new HTTPClientRequest(serverAddress.getHostString(), serverAddress.getPort(), useSSL);
			FormDataEntity form = new FormDataEntity();
			form.addFile("myfile", "the_filename", new ParameterizedHeaderValue("application/octet-stream"), new ByteArrayIO(new byte[] { 0, 1, 2, 3, 4, 5}, "the_file"));
			form.addField("myfield", "valueofmyfield", StandardCharsets.US_ASCII);
			form.addField("myfield2", "valueofmyfield2", StandardCharsets.US_ASCII);
			form.addFile("f2", "second.bin", new ParameterizedHeaderValue("application/octet-stream"), new ByteArrayIO(new byte[] { 0, 1, 2, 3, 4, 5}, "the_file"));
			form.addFile("f3", "third.bin", new ParameterizedHeaderValue("application/octet-stream"), new ByteArrayIO(new byte[] { 0, 1, 2, 3, 4, 5}, "the_file"));
			request = new HTTPClientRequest(serverAddress.getHostString(), serverAddress.getPort(), useSSL);
			request.post("/test/post?status=200", form);
			HTTPClientResponse resp = client.send(request);
			resp.getBodyReceived().blockThrow(0);
			Assert.assertEquals(200, resp.getStatusCode());
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
	
	protected static void check(HTTPResponse response, int status, String expectedXTest) throws Exception {
		Assert.assertEquals("Status code", status, response.getStatusCode());
		if (expectedXTest != null)
			Assert.assertEquals("X-Test header", expectedXTest, response.getHeaders().getFirstRawValue("X-Test"));
		else
			Assert.assertFalse(response.getHeaders().has("X-Test"));
	}
	
	@Test
	public void testStaticProcessor() throws Exception {
		startServer(new StaticProcessor("net/lecousin/framework/network/http/test", null));
		try (HTTPClientRequestSender client = createClient()) {
			HTTPClientRequestContext ctx = new HTTPClientRequestContext(client, new HTTPClientRequest(serverAddress, useSSL).get("/myresource.txt"));
			ctx.setEntityFactory(BinaryEntity::new);
			client.send(ctx);
			HTTPClientResponse response = ctx.getResponse();
			response.getTrailersReceived().blockThrow(0);
			String s = IOUtil.readFullyAsStringSync(((BinaryEntity)response.getEntity()).getContent(), StandardCharsets.US_ASCII);
			Assert.assertEquals("This is my resource", s);
		}
	}

	@Test
	public void testPostLargeBody() throws Exception {
		stopLogging();
		AbstractNetworkTest.deactivateNetworkTraces();
		startServer(new ProcessorForTests());
		byte[] buf = new byte[10 * 1024 * 1024];
		for (int i = 0; i < buf.length; ++i)
			buf[i] = (byte)(i * 7 % 621);

		try (HTTPClientRequestSender client = createClient()) {
			HTTPClientRequestContext ctx = new HTTPClientRequestContext(client, new HTTPClientRequest(serverAddress, useSSL).post("/test/post?status=200", new BinaryEntity(new ByteArrayIO(buf, "test"))));
			ctx.setEntityFactory(BinaryEntity::new);
			client.send(ctx);
			HTTPClientResponse response = ctx.getResponse();
			response.getTrailersReceived().blockThrow(0);
			PreBufferedReadable bio = new PreBufferedReadable(((BinaryEntity)response.getEntity()).getContent(), 8192, Task.Priority.NORMAL, 16384, Task.Priority.NORMAL, 10);
			for (int i = 0; i < buf.length; ++i)
				Assert.assertEquals("Byte at " + i, buf[i] & 0xFF, bio.read());
			Assert.assertEquals(-1, bio.read());
			bio.close();
		} finally {
			resumeLogging();
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
		startServer(new TestTrailerTimeProcessor());
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.INFO);
		LCCore.getApplication().getLoggerFactory().getLogger(HTTP1ServerProtocol.class).setLevel(Level.INFO);
		try (HTTPClientRequestSender client = createClient()) {
			HTTPClientRequestContext ctx = new HTTPClientRequestContext(client, new HTTPClientRequest(serverAddress, useSSL).get("/tutu"));
			ctx.setEntityFactory(BinaryEntity::new);
			client.send(ctx);
			HTTPClientResponse response = ctx.getResponse();
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
	
	protected abstract void enableRangeRequests();
	
	@Test
	public void testRangeRequests() throws Exception {
		startServer(new RangeProcessor("net/lecousin/framework/network/http/test"));
		enableRangeRequests();
		try (HTTPClientRequestSender client = createClient()) {
			HTTPClientRequest request;
			HTTPClientResponse response;

			request = new HTTPClientRequest("localhost", serverAddress.getPort(), useSSL).get();
			request.setDecodedPath("/myresource.txt").setHeader("Range", "bytes=-2");
			response = client.send(request);
			response.getTrailersReceived().blockThrow(0);
			Assert.assertEquals(206, response.getStatusCode());
			String s = IOUtil.readFullyAsStringSync(((BinaryEntity)response.getEntity()).getContent(), StandardCharsets.US_ASCII);
			Assert.assertEquals("ce", s);
			
			request = new HTTPClientRequest("localhost", serverAddress.getPort(), useSSL).get();
			request.setDecodedPath("/myresource.txt").setHeader("Range", "bytes=3-6");
			response = client.send(request);
			response.getTrailersReceived().blockThrow(0);
			Assert.assertEquals(206, response.getStatusCode());
			s = IOUtil.readFullyAsStringSync(((BinaryEntity)response.getEntity()).getContent(), StandardCharsets.US_ASCII);
			Assert.assertEquals("s is", s);
			
			request = new HTTPClientRequest("localhost", serverAddress.getPort(), useSSL).get();
			request.setDecodedPath("/myresource.txt").setHeader("Range", "bytes=12-");
			response = client.send(request);
			response.getTrailersReceived().blockThrow(0);
			Assert.assertEquals(206, response.getStatusCode());
			s = IOUtil.readFullyAsStringSync(((BinaryEntity)response.getEntity()).getContent(), StandardCharsets.US_ASCII);
			Assert.assertEquals("esource", s);
			
			request = new HTTPClientRequest("localhost", serverAddress.getPort(), useSSL).get();
			request.setDecodedPath("/myresource.txt").setHeader("Range", "bytes=3-6,12-");
			response = client.send(request);
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
