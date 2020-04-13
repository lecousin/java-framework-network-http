package net.lecousin.framework.network.websocket.test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.SSLContext;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.application.Artifact;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.application.Version;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.io.IO.Readable.Seekable;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.util.EmptyReadable;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.mutable.Mutable;
import net.lecousin.framework.mutable.MutableInteger;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientResponse;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http.server.processor.ProxyHTTPRequestProcessor;
import net.lecousin.framework.network.http.server.processor.StaticProcessor;
import net.lecousin.framework.network.http1.client.HTTP1ClientConnection;
import net.lecousin.framework.network.http1.server.HTTP1ServerProtocol;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.SSLServerProtocol;
import net.lecousin.framework.network.test.AbstractNetworkTest;
import net.lecousin.framework.network.websocket.WebSocketClient;
import net.lecousin.framework.network.websocket.WebSocketDataFrame;
import net.lecousin.framework.network.websocket.WebSocketServerProtocol;
import net.lecousin.framework.network.websocket.WebSocketServerProtocol.WebSocketMessageListener;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.BlockJUnit4ClassRunner;

@RunWith(BlockJUnit4ClassRunner.class)
public class TestWebSocket extends AbstractNetworkTest {

	@SuppressWarnings("resource")
	public static void main(String[] args) {
		try {
			Application.start(new Artifact("net.lecousin.framework.network.http", "websocket.test", new Version("0")), args, true).blockThrow(0);
			Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(TestWebSocket.class);
			TCPServer http_server = new TCPServer();
			HTTP1ServerProtocol protocol = new HTTP1ServerProtocol(new StaticProcessor("net/lecousin/framework/network/http/test/websocket", null));
			WebSocketServerProtocol wsProtocol = new WebSocketServerProtocol(new WebSocketMessageListener() {
				@Override
				public String onClientConnected(WebSocketServerProtocol websocket, TCPServerClient client, String[] requestedProtocols) {
					System.out.println("WebSocket client connected");
					return null;
				}
				@Override
				public void onTextMessage(WebSocketServerProtocol websocket, TCPServerClient client, String message) {
					System.out.println("WebSocket text message received: "+message);
					WebSocketServerProtocol.sendTextMessage(client, "Hello World!", logger);
				}
				@Override
				public void onBinaryMessage(WebSocketServerProtocol websocket, TCPServerClient client, Seekable message) {
					System.out.println("WebSocket binary message received");
				}
			});
			protocol.addUpgradeProtocol(wsProtocol);
			http_server.setProtocol(protocol);
			http_server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 1111), 10).blockThrow(0);
			Thread.sleep(5*60*1000);
			LCCore.stop(true);
		} catch (Throwable t) {
			t.printStackTrace(System.err);
		}
	}
	
	private MutableInteger connected;
	private TCPServer server;
	private TCPServer sslServer;
	private SocketAddress serverAddress;
	private SocketAddress serverSSLAddress;
	
	@Before
	public void startServer() throws Exception {
		deactivateNetworkTraces();
		server = new TCPServer();
		HTTP1ServerProtocol protocol = new HTTP1ServerProtocol(new StaticProcessor("net/lecousin/framework/network/http/test/websocket", null));
		connected = new MutableInteger(0);
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(TestWebSocket.class);
		WebSocketServerProtocol wsProtocol = new WebSocketServerProtocol(new WebSocketMessageListener() {
			@Override
			public String onClientConnected(WebSocketServerProtocol websocket, TCPServerClient client, String[] requestedProtocols) throws HTTPResponseError {
				System.out.println("WebSocket client connected");
				connected.inc();
				for (String p : requestedProtocols)
					if ("test2".equals(p))
						return "test2";
					else if ("test_error".equals(p))
						throw new HTTPResponseError(501, "test");
				return null;
			}
			@Override
			public void onTextMessage(WebSocketServerProtocol websocket, TCPServerClient client, String message) {
				System.out.println("WebSocket text message received from client: "+message);
				WebSocketServerProtocol.sendTextMessage(client, "Hello " + message + "!", logger);
			}
			@Override
			public void onBinaryMessage(WebSocketServerProtocol websocket, TCPServerClient client, Seekable message) {
				System.out.println("WebSocket binary message received from client");
				byte[] buf = new byte[1024];
				int nb;
				try { nb = IOUtil.readFully(message, ByteBuffer.wrap(buf)); }
				catch (IOException e) {
					e.printStackTrace(System.err);
					return;
				}
				byte[] resp = new byte[nb];
				for (int i = 0; i < nb; ++i)
					resp[i] = buf[nb-1-i];
				WebSocketServerProtocol.sendBinaryMessage(client, new ByteArrayIO(resp, "binary message"), logger);
			}
		});
		protocol.addUpgradeProtocol(wsProtocol);
		server.setProtocol(protocol);
		serverAddress = server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 10).blockResult(0);
		
		sslServer = new TCPServer();
		sslServer.setProtocol(new SSLServerProtocol(sslTest, protocol));
		serverSSLAddress = sslServer.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 10).blockResult(0);
	}
	
	@After
	public void stopServer() {
		server.close();
		sslServer.close();
	}
	
	private URI getServerURI() throws URISyntaxException {
		return new URI("ws://localhost:" + ((InetSocketAddress)serverAddress).getPort() + "/");
	}
	
	private URI getSSLServerURI() throws URISyntaxException {
		return new URI("wss://localhost:" + ((InetSocketAddress)serverSSLAddress).getPort() + "/");
	}
	
	@Test
	public void testWrongProtocol() throws Exception {
		// try a wrong protocol
		Assert.assertEquals(0, connected.get());
		WebSocketClient client = new WebSocketClient();
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), new HTTPClientConfiguration(), "hello", "world");
		conn.block(0);
		client.close();
		Assert.assertEquals(1, connected.get());
		Assert.assertFalse(conn.isSuccessful());
	}
	
	@Test
	public void testCorrectProtocol() throws Exception {
		// try a correct protocol
		WebSocketClient client = new WebSocketClient();
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), new HTTPClientConfiguration(), "test1", "test2", "test3");
		String selected = conn.blockResult(0);
		Assert.assertEquals(1, connected.get());
		Assert.assertEquals("test2", selected);
		client.close();
	}
	
	@Test
	public void testNoProtocol() throws Exception {
		// try a correct protocol
		WebSocketClient client = new WebSocketClient();
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), new HTTPClientConfiguration());
		try {
			conn.blockResult(0);
			throw new AssertionError("No protocol must raise an error");
		} catch (Exception e) {}
		finally { client.close(); }
	}
	
	@Test
	public void testRejectedProtocol() throws Exception {
		// try a correct protocol
		WebSocketClient client = new WebSocketClient();
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), new HTTPClientConfiguration(), "test1", "test_error", "test3");
		try {
			conn.blockResult(0);
			throw new AssertionError("Connection with protocol test_error must fail");
		} catch (IOException e) {
			Assert.assertTrue(e.getMessage().contains("501"));
		} finally {
			client.close();
		}
	}
	
	@Test
	public void testSSL() throws Exception {
		// try a correct protocol
		WebSocketClient client = new WebSocketClient();
		HTTPClientConfiguration config = new HTTPClientConfiguration(new HTTPClientConfiguration());
		config.setSSLContext(sslTest);
		AsyncSupplier<String, IOException> conn = client.connect(getSSLServerURI(), config, "test1", "test2", "test3");
		String selected = conn.blockResult(0);
		Assert.assertEquals(1, connected.get());
		Assert.assertEquals("test2", selected);
		client.close();
	}
		
	@Test
	public void testTextMessages() throws Exception {
		// try text messages
		WebSocketClient client = new WebSocketClient();
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), new HTTPClientConfiguration(), "test1", "test2", "test3");
		String selected = conn.blockResult(0);
		Assert.assertEquals(1, connected.get());
		Assert.assertEquals("test2", selected);
		Mutable<String> received = new Mutable<>(null);
		Mutable<Async<Exception>> sp = new Mutable<>(new Async<>());
		client.onMessage((frame) -> {
			try {
				IOInMemoryOrFile msg = frame.getMessage();
				msg.seekSync(SeekType.FROM_BEGINNING, 0);
				received.set(IOUtil.readFullyAsStringSync(msg, StandardCharsets.UTF_8));
				sp.get().unblock();
			} catch (Exception e) {
				sp.get().error(e);
			}
		});
		client.sendTextMessage("Test").blockThrow(0);
		sp.get().blockThrow(5000);
		Assert.assertTrue(sp.get().isDone());
		Assert.assertEquals("Hello Test!", received.get());
		sp.set(new Async<>());
		client.sendTextMessage("World").blockThrow(0);
		sp.get().blockThrow(5000);
		Assert.assertTrue(sp.get().isDone());
		Assert.assertEquals("Hello World!", received.get());
		client.close();
	}
	
	@Test
	public void testBinaryMessages() throws Exception {
		// try binary message
		WebSocketClient client = new WebSocketClient();
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), new HTTPClientConfiguration(), "test1", "test2", "test3");
		String selected = conn.blockResult(0);
		Assert.assertEquals(1, connected.get());
		Assert.assertEquals("test2", selected);
		Mutable<ByteBuffer> binary = new Mutable<>(null);
		Mutable<Async<Exception>> sp = new Mutable<>(new Async<>());
		client.onMessage((frame) -> {
			try {
				IOInMemoryOrFile msg = frame.getMessage();
				msg.seekSync(SeekType.FROM_BEGINNING, 0);
				ByteBuffer buf = ByteBuffer.allocate(1024);
				IOUtil.readFully(msg, buf);
				binary.set(buf);
				sp.get().unblock();
			} catch (Exception e) {
				sp.get().error(e);
			}
		});
		client.sendBinaryMessage(new ByteArrayIO(new byte[] { 0, 1, 2, 3 }, "Test")).blockThrow(0);
		sp.get().blockThrow(5000);
		Assert.assertTrue(sp.get().isDone());
		ByteBuffer buf = binary.get();
		buf.flip();
		byte[] resp = new byte[buf.remaining()];
		buf.get(resp);
		Assert.assertArrayEquals(new byte[] { 3, 2, 1, 0 }, resp);
		client.close();
	}
		
	@Test
	public void testBigBinaryMessages() throws Exception {
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.INFO);
		// try binary message
		WebSocketClient client = new WebSocketClient();
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), new HTTPClientConfiguration(), "test1", "test2", "test3");
		String selected = conn.blockResult(0);
		Assert.assertEquals(1, connected.get());
		Assert.assertEquals("test2", selected);
		Mutable<ByteBuffer> binary = new Mutable<>(null);
		Mutable<Async<Exception>> sp = new Mutable<>(new Async<>());
		client.onMessage((frame) -> {
			try {
				IOInMemoryOrFile msg = frame.getMessage();
				msg.seekSync(SeekType.FROM_BEGINNING, 0);
				ByteBuffer buf = ByteBuffer.allocate(1024);
				IOUtil.readFully(msg, buf);
				binary.set(buf);
				sp.get().unblock();
			} catch (Exception e) {
				sp.get().error(e);
			}
		});
		byte[] big = new byte[1024 * 1024];
		for (int i = 0; i < big.length; ++i) big[i] = (byte)((i + 11) % 67);
		client.sendBinaryMessage(new ByteArrayIO(big, "Test")).blockThrow(0);
		sp.get().blockThrow(5000);
		Assert.assertTrue(sp.get().isDone());
		ByteBuffer buf = binary.get();
		buf.flip();
		byte[] resp = new byte[buf.remaining()];
		buf.get(resp);
		for (int i = 0; i < 1024; ++i)
			Assert.assertEquals(big[1024 - i - 1], resp[i]);
		client.close();
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.TRACE);
	}
		
	@Test
	public void testPing() throws Exception {
		// test ping
		WebSocketClient client = new WebSocketClient();
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), new HTTPClientConfiguration(), "test1", "test2", "test3");
		String selected = conn.blockResult(0);
		Assert.assertEquals(1, connected.get());
		Assert.assertEquals("test2", selected);
		Mutable<Async<Exception>> sp = new Mutable<>(new Async<>());
		client.onMessage((frame) -> {
			if (frame.getMessageType() != WebSocketDataFrame.TYPE_PONG)
				sp.get().error(new Exception("Unexpected message: " + frame.getMessageType()));
			else
				sp.get().unblock();
		});
		client.sendPing().blockThrow(0);
		sp.get().blockThrow(5000);
		Assert.assertTrue(sp.get().isDone());
		client.close();
	}
		
	@Test
	public void testClose() throws Exception {
		// test close
		WebSocketClient client = new WebSocketClient();
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), new HTTPClientConfiguration(), "test1", "test2", "test3");
		String selected = conn.blockResult(0);
		Assert.assertEquals(1, connected.get());
		Assert.assertEquals("test2", selected);
		Mutable<Async<Exception>> sp = new Mutable<>(new Async<>());
		client.onMessage((frame) -> {
			if (frame.getMessageType() != WebSocketDataFrame.TYPE_CLOSE)
				sp.get().error(new Exception("Unexpected message: " + frame.getMessageType()));
			else
				sp.get().unblock();
		});
		client.sendClose().blockThrow(0);
		sp.get().blockThrow(5000);
		Assert.assertTrue(sp.get().isDone());
		client.close();		
	}
	
	
	@Test
	public void testInvalidMessage() throws Exception {
		WebSocketClient client = new WebSocketClient();
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), new HTTPClientConfiguration(), "test1", "test2", "test3");
		String selected = conn.blockResult(0);
		Assert.assertEquals(1, connected.get());
		Assert.assertEquals("test2", selected);
		Mutable<Async<Exception>> sp = new Mutable<>(new Async<>());
		client.onMessage((frame) -> {
			if (frame.getMessageType() != WebSocketDataFrame.TYPE_PONG)
				sp.get().error(new Exception("Unexpected message: " + frame.getMessageType()));
			else
				sp.get().unblock();
		});
		client.sendMessage(9999, new EmptyReadable("empty", Task.Priority.NORMAL)).blockThrow(0);
		sp.get().blockThrow(2000);
		Assert.assertFalse(sp.get().isDone());
		client.close();
	}

	
	@Test
	public void testProxyConfigurations() throws Exception {
		WebSocketClient client = new WebSocketClient();
		HTTPClientConfiguration config = new HTTPClientConfiguration();
		config.setProxySelector(null);
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), config, "test1", "test2", "test3");
		String selected = conn.blockResult(0);
		Assert.assertEquals(1, connected.get());
		Assert.assertEquals("test2", selected);
		client.close();
		
		// start proxy server
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger("test-proxy");
		logger.setLevel(Level.TRACE);
		config = new HTTPClientConfiguration(new HTTPClientConfiguration());
		config.setSSLContext(SSLContext.getDefault());
		try (TCPServer proxyServer = new TCPServer(); HTTPClient proxyClient = new HTTPClient(config)) {
			ProxyHTTPRequestProcessor processor = new ProxyHTTPRequestProcessor(proxyClient, 8192, 10000, 10000, logger);
			HTTP1ServerProtocol protocol = new HTTP1ServerProtocol(processor);
			proxyServer.setProtocol(protocol);
			SocketAddress proxyAddress = proxyServer.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);
			int proxyPort = ((InetSocketAddress)proxyAddress).getPort();
			
			// test websocket through proxy
			config = new HTTPClientConfiguration(new HTTPClientConfiguration());
			config.setProxySelector(new ProxySelector() {
				private Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", proxyPort));
				@Override
				public List<Proxy> select(URI uri) {
					return Collections.singletonList(proxy);
				}
				
				@Override
				public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
				}
			});
			conn = client.connect(getServerURI(), config, "test1", "test2", "test3");
			selected = conn.blockResult(0);
			Assert.assertEquals(2, connected.get());
			Assert.assertEquals("test2", selected);
			client.close();
			
			// test with HTTPS
			config.setSSLContext(sslTest);
			conn = client.connect(getSSLServerURI(), config, "test1", "test2", "test3");
			selected = conn.blockResult(0);
			Assert.assertEquals(3, connected.get());
			Assert.assertEquals("test2", selected);
			client.close();
		}
		// proxy server stopped
		
		// test wrong proxy
		config.setProxySelector(new ProxySelector() {
			private Proxy proxy = new Proxy(Proxy.Type.HTTP, serverAddress);
			@Override
			public List<Proxy> select(URI uri) {
				return Collections.singletonList(proxy);
			}
			
			@Override
			public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
			}
		});
		conn = client.connect(getServerURI(), config, "test1", "test2", "test3");
		try {
			conn.blockResult(0);
			throw new AssertionError("Using wrong proxy address should throw an error");
		} catch (HTTPResponseError e) {
			// ok
		}
		Assert.assertEquals(3, connected.get());
		client.close();
	}
	
	@Test
	public void testErrors() throws Exception {
		int port = ((InetSocketAddress)serverAddress).getPort();
		HTTPClientConfiguration config = new HTTPClientConfiguration();
		HTTPClientRequest request;
		HTTPClientResponse response;
		
		request = new HTTPClientRequest("localhost", port, false);
		request.get("/test").setHeaders(new MimeHeaders(
			new MimeHeader("Upgrade", "websocket")
		));
		response = HTTP1ClientConnection.send(request, config);
		response.getHeadersReceived().blockThrow(0);
		Assert.assertEquals(404, response.getStatusCode());

		request = new HTTPClientRequest("localhost", port, false);
		request.get("/test").setHeaders(new MimeHeaders(
			new MimeHeader(HTTPConstants.Headers.CONNECTION, "Upgrade"),
			new MimeHeader("Upgrade", "websocket")
		));
		response = HTTP1ClientConnection.send(request, config);
		response.getHeadersReceived().blockThrow(0);
		Assert.assertEquals(400, response.getStatusCode());

		request = new HTTPClientRequest("localhost", port, false);
		request.get("/test").setHeaders(new MimeHeaders(
			new MimeHeader(HTTPConstants.Headers.CONNECTION, "Upgrade"),
			new MimeHeader("Upgrade", "unknown")
		));
		response = HTTP1ClientConnection.send(request, config);
		response.getHeadersReceived().blockThrow(0);
		Assert.assertEquals(404, response.getStatusCode());

		request = new HTTPClientRequest("localhost", port, false);
		request.get("/test").setHeaders(new MimeHeaders(
			new MimeHeader(HTTPConstants.Headers.CONNECTION, "Upgrade"),
			new MimeHeader("Upgrade", "websocket"),
			new MimeHeader("Sec-WebSocket-Key", "hello")
		));
		response = HTTP1ClientConnection.send(request, config);
		response.getHeadersReceived().blockThrow(0);
		Assert.assertEquals(400, response.getStatusCode());

		request = new HTTPClientRequest("localhost", port, false);
		request.get("/test").setHeaders(new MimeHeaders(
			new MimeHeader(HTTPConstants.Headers.CONNECTION, "Upgrade"),
			new MimeHeader("Upgrade", "websocket"),
			new MimeHeader("Sec-WebSocket-Key", "")
		));
		response = HTTP1ClientConnection.send(request, config);
		response.getHeadersReceived().blockThrow(0);
		Assert.assertEquals(400, response.getStatusCode());

		request = new HTTPClientRequest("localhost", port, false);
		request.get("/test").setHeaders(new MimeHeaders(
			new MimeHeader(HTTPConstants.Headers.CONNECTION, "Upgrade"),
			new MimeHeader("Upgrade", "websocket"),
			new MimeHeader("Sec-WebSocket-Key", "hello"),
			new MimeHeader("Sec-WebSocket-Version", "51")
		));
		response = HTTP1ClientConnection.send(request, config);
		response.getHeadersReceived().blockThrow(0);
		Assert.assertEquals(400, response.getStatusCode());

		request = new HTTPClientRequest("localhost", port, false);
		request.get("/test").setHeaders(new MimeHeaders(
			new MimeHeader(HTTPConstants.Headers.CONNECTION, "Upgrade"),
			new MimeHeader("Upgrade", "websocket"),
			new MimeHeader("Sec-WebSocket-Key", "hello"),
			new MimeHeader("Sec-WebSocket-Version", "")
		));
		response = HTTP1ClientConnection.send(request, config);
		response.getHeadersReceived().blockThrow(0);
	}
}
