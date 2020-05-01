package net.lecousin.framework.network.http1.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientConnection;
import net.lecousin.framework.network.http.client.HTTPClientConnection.OpenConnection;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientRequestSender;
import net.lecousin.framework.network.http.client.HTTPClientResponse;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;
import net.lecousin.framework.network.http.test.AbstractTestHttpServer;
import net.lecousin.framework.network.http.test.ProcessorForTests;
import net.lecousin.framework.network.http1.client.HTTP1ClientConnection;
import net.lecousin.framework.network.http1.server.HTTP1ServerProtocol;
import net.lecousin.framework.network.mime.header.MimeHeaders.HeadersConsumer;
import net.lecousin.framework.network.server.protocol.ALPNServerProtocol;
import net.lecousin.framework.network.server.protocol.ServerProtocol;
import net.lecousin.framework.network.ssl.SSLConnectionConfig;

import org.junit.Assert;
import org.junit.Test;

public class TestHTTP1Server extends AbstractTestHttpServer {

	public TestHTTP1Server(boolean useSSL) {
		super(useSSL);
	}
	
	private HTTP1ServerProtocol protocol;
	
	@Override
	protected void stopLogging() {
		LCCore.getApplication().getLoggerFactory().getLogger(HTTP1ServerProtocol.class).setLevel(Level.ERROR);
		LCCore.getApplication().getLoggerFactory().getLogger(HeadersConsumer.class).setLevel(Level.ERROR);
	}
	
	@Override
	protected void resumeLogging() {
		LCCore.getApplication().getLoggerFactory().getLogger(HTTP1ServerProtocol.class).setLevel(Level.DEBUG);
		LCCore.getApplication().getLoggerFactory().getLogger(HeadersConsumer.class).setLevel(Level.DEBUG);
	}
	
	@Override
	protected ServerProtocol createProtocol(HTTPRequestProcessor processor) {
		protocol = new HTTP1ServerProtocol(processor);
		protocol.setReceiveDataTimeout(10000);
		Assert.assertEquals(10000, protocol.getReceiveDataTimeout());
		protocol.getProcessor();
		return protocol;
	}
	
	@Override
	protected ALPNServerProtocol[] getALPNProtocols() {
		return new ALPNServerProtocol[0];
	}
	
	@Override
	protected void enableRangeRequests() {
		protocol.enableRangeRequests();
	}
	
	@Override
	protected HTTPClientRequestSender createClient() {
		return new HTTP1ClientConnection(1, clientConfig);
	}
	
	@Test
	public void testConcurrentRequests() throws Exception {
		startServer(new ProcessorForTests());
		// open connection
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(TestHTTP1Server.class);
		OpenConnection conn = HTTPClientConnection.openConnection(
			"localhost", serverAddress.getPort(), "/test/get?status=200&test=hello", useSSL, clientConfig, logger);
		TCPClient client = conn.getClient();;
		IAsync<IOException> connection = conn.getConnect();
		// tests
		HTTPClientRequest req1 = new HTTPClientRequest("localhost", serverAddress.getPort(), false).get("/test/get?status=200&test=hello");
		HTTPClientRequest req2 = new HTTPClientRequest("localhost", serverAddress.getPort(), false).get("/test/get?status=602");
		HTTPClientRequest req3 = new HTTPClientRequest("localhost", serverAddress.getPort(), false).get("/test/get?status=603");
		HTTPClientRequest req4 = new HTTPClientRequest("localhost", serverAddress.getPort(), false).get("/test/get?status=604");
		HTTPClientRequest req5 = new HTTPClientRequest("localhost", serverAddress.getPort(), false).get("/test/get?status=605");
		HTTPClientRequest req6 = new HTTPClientRequest("localhost", serverAddress.getPort(), false).get("/test/get?status=606");
		HTTPClientRequest req7 = new HTTPClientRequest("localhost", serverAddress.getPort(), false).get("/test/get?status=607");
		HTTPClientRequest req8 = new HTTPClientRequest("localhost", serverAddress.getPort(), false).get("/test/get?status=608");
		HTTPClientRequest req9 = new HTTPClientRequest("localhost", serverAddress.getPort(), false).get("/test/get?status=609");
		HTTPClientRequest req10 = new HTTPClientRequest("localhost", serverAddress.getPort(), false).get("/test/get?status=610");

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
		
		HTTPClientResponse resp1 = HTTP1ClientConnection.receiveResponse(client, conn.isThroughProxy(), req1, clientConfig);
		resp1.getTrailersReceived().blockThrow(0);
		HTTPClientResponse resp2 = HTTP1ClientConnection.receiveResponse(client, conn.isThroughProxy(), req2, clientConfig);
		resp2.getTrailersReceived().blockThrow(0);
		HTTPClientResponse resp3 = HTTP1ClientConnection.receiveResponse(client, conn.isThroughProxy(), req3, clientConfig);
		resp3.getTrailersReceived().blockThrow(0);
		HTTPClientResponse resp4 = HTTP1ClientConnection.receiveResponse(client, conn.isThroughProxy(), req4, clientConfig);
		resp4.getTrailersReceived().blockThrow(0);
		HTTPClientResponse resp5 = HTTP1ClientConnection.receiveResponse(client, conn.isThroughProxy(), req5, clientConfig);
		resp5.getTrailersReceived().blockThrow(0);
		HTTPClientResponse resp6 = HTTP1ClientConnection.receiveResponse(client, conn.isThroughProxy(), req6, clientConfig);
		resp6.getTrailersReceived().blockThrow(0);
		HTTPClientResponse resp7 = HTTP1ClientConnection.receiveResponse(client, conn.isThroughProxy(), req7, clientConfig);
		resp7.getTrailersReceived().blockThrow(0);
		HTTPClientResponse resp8 = HTTP1ClientConnection.receiveResponse(client, conn.isThroughProxy(), req8, clientConfig);
		resp8.getTrailersReceived().blockThrow(0);
		HTTPClientResponse resp9 = HTTP1ClientConnection.receiveResponse(client, conn.isThroughProxy(), req9, clientConfig);
		resp9.getTrailersReceived().blockThrow(0);
		HTTPClientResponse resp10 = HTTP1ClientConnection.receiveResponse(client, conn.isThroughProxy(), req10, clientConfig);
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
	}

	
	@Test
	public void testSendRequestBySmallPackets() throws Exception {
		startServer(new ProcessorForTests());
		SSLConnectionConfig sslConfig = null;
		if (useSSL) {
			sslConfig = new SSLConnectionConfig();
			sslConfig.setContext(sslTest);
		}
		try (TCPClient client = useSSL ? new SSLClient(sslConfig) : new TCPClient()) {
			client.connect(new InetSocketAddress("localhost", serverAddress.getPort()), 10000).blockThrow(0);
			String req =
				"GET /test/get?status=200&test=world HTTP/1.1\r\n" +
				"Host: localhost:" + serverAddress.getPort() + "\r\n" +
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
			HTTPClientResponse resp = HTTP1ClientConnection.receiveResponse(client, false, new HTTPClientRequest("localhost", serverAddress.getPort(), false), config);
			resp.getTrailersReceived().blockThrow(0);
			
			check(resp, 200, "world");
		}
	}
	
	@Test
	public void testInvalidRequestes() throws Exception {
		startServer(new ProcessorForTests());
		testInvalidRequest("TOTO /titi\r\n\r\n", 400);
		testInvalidRequest("GET /titi\r\n\r\n", 400);
		testInvalidRequest("GET\r\n\r\n", 400);
		testInvalidRequest("GET tutu FTP/10.51\r\n\r\n", 400);
		testInvalidRequest("GET /test HTTP/1.1\r\n\tFollowing: nothing\r\n\r\n", 400);
		//testInvalidRequest("POST /test/post?status=200 HTTP/1.1\r\nTransfer-Encoding: identity\r\n\r\n", serverPort, 400);
		testInvalidRequest("GET /titi HTTP/1.1\r\n\r\n", 502);
	}
	
	private void testInvalidRequest(String request, int expectedStatus) throws Exception {
		SSLConnectionConfig sslConfig = null;
		if (useSSL) {
			sslConfig = new SSLConnectionConfig();
			sslConfig.setContext(sslTest);
		}
		try (TCPClient client = useSSL ? new SSLClient(sslConfig) : new TCPClient()) {
			client.connect(new InetSocketAddress("localhost", serverAddress.getPort()), 10000).blockThrow(0);
			client.send(ByteBuffer.wrap(request.getBytes(StandardCharsets.US_ASCII)), 10000);
			HTTPClientConfiguration config = new HTTPClientConfiguration();
			HTTPClientResponse response = HTTP1ClientConnection.receiveResponse(client, false, new HTTPClientRequest("localhost", serverAddress.getPort(), false), config);
			response.getTrailersReceived().blockThrow(0);
			Assert.assertEquals(expectedStatus, response.getStatusCode());
		}
	}
	
}
