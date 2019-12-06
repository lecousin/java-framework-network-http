package net.lecousin.framework.network.http.test;

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
import net.lecousin.framework.io.IO.Readable.Seekable;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.mutable.Mutable;
import net.lecousin.framework.mutable.MutableInteger;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientUtil;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http.server.HTTPServerProtocol;
import net.lecousin.framework.network.http.server.processor.ProxyHTTPRequestProcessor;
import net.lecousin.framework.network.http.server.processor.StaticProcessor;
import net.lecousin.framework.network.http.websocket.WebSocketClient;
import net.lecousin.framework.network.http.websocket.WebSocketDataFrame;
import net.lecousin.framework.network.http.websocket.WebSocketServerProtocol;
import net.lecousin.framework.network.http.websocket.WebSocketServerProtocol.WebSocketMessageListener;
import net.lecousin.framework.network.mime.MimeHeader;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.SSLServerProtocol;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.BlockJUnit4ClassRunner;

@RunWith(BlockJUnit4ClassRunner.class)
public class TestWebSocket extends AbstractHTTPTest {

	@SuppressWarnings("resource")
	public static void main(String[] args) {
		try {
			Application.start(new Artifact("net.lecousin.framework.network.http", "websocket.test", new Version("0")), args, true).blockThrow(0);
			TCPServer http_server = new TCPServer();
			HTTPServerProtocol protocol = new HTTPServerProtocol(new StaticProcessor("net/lecousin/framework/network/http/test/websocket"));
			WebSocketServerProtocol wsProtocol = new WebSocketServerProtocol(new WebSocketMessageListener() {
				@Override
				public String onClientConnected(WebSocketServerProtocol websocket, TCPServerClient client, String[] requestedProtocols) {
					System.out.println("WebSocket client connected");
					return null;
				}
				@Override
				public void onTextMessage(WebSocketServerProtocol websocket, TCPServerClient client, String message) {
					System.out.println("WebSocket text message received: "+message);
					WebSocketServerProtocol.sendTextMessage(client, "Hello World!");
				}
				@Override
				public void onBinaryMessage(WebSocketServerProtocol websocket, TCPServerClient client, Seekable message) {
					System.out.println("WebSocket binary message received");
				}
			});
			protocol.enableWebSocket(wsProtocol);
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
		server = new TCPServer();
		HTTPServerProtocol protocol = new HTTPServerProtocol(new StaticProcessor("net/lecousin/framework/network/http/test/websocket"));
		connected = new MutableInteger(0);
		WebSocketServerProtocol wsProtocol = new WebSocketServerProtocol(new WebSocketMessageListener() {
			@Override
			public String onClientConnected(WebSocketServerProtocol websocket, TCPServerClient client, String[] requestedProtocols) {
				System.out.println("WebSocket client connected");
				connected.inc();
				for (String p : requestedProtocols)
					if ("test2".equals(p))
						return "test2";
				return null;
			}
			@Override
			public void onTextMessage(WebSocketServerProtocol websocket, TCPServerClient client, String message) {
				System.out.println("WebSocket text message received from client: "+message);
				WebSocketServerProtocol.sendTextMessage(client, "Hello " + message + "!");
			}
			@SuppressWarnings("resource")
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
				WebSocketServerProtocol.sendBinaryMessage(client, new ByteArrayIO(resp, "binary message"));
			}
		});
		protocol.enableWebSocket(wsProtocol);
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
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), HTTPClientConfiguration.defaultConfiguration, "hello", "world");
		conn.block(0);
		client.close();
		Assert.assertEquals(1, connected.get());
		Assert.assertFalse(conn.isSuccessful());
	}
	
	@Test
	public void testCorrectProtocol() throws Exception {
		// try a correct protocol
		WebSocketClient client = new WebSocketClient();
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), HTTPClientConfiguration.defaultConfiguration, "test1", "test2", "test3");
		String selected = conn.blockResult(0);
		Assert.assertEquals(1, connected.get());
		Assert.assertEquals("test2", selected);
		client.close();
	}
	
	@Test
	public void testNoProtocol() throws Exception {
		// try a correct protocol
		WebSocketClient client = new WebSocketClient();
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), HTTPClientConfiguration.defaultConfiguration);
		try {
			conn.blockResult(0);
			throw new AssertionError("No protocol must raise an error");
		} catch (Exception e) {}
		finally { client.close(); }
	}
	
	@Test
	public void testSSL() throws Exception {
		// try a correct protocol
		WebSocketClient client = new WebSocketClient();
		HTTPClientConfiguration config = new HTTPClientConfiguration(HTTPClientConfiguration.defaultConfiguration);
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
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), HTTPClientConfiguration.defaultConfiguration, "test1", "test2", "test3");
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
		Assert.assertEquals("Hello Test!", received.get());
		sp.set(new Async<>());
		client.sendTextMessage("World").blockThrow(0);
		sp.get().blockThrow(5000);
		Assert.assertEquals("Hello World!", received.get());
		client.close();
	}
	
	@Test
	public void testBinaryMessages() throws Exception {
		// try binary message
		WebSocketClient client = new WebSocketClient();
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), HTTPClientConfiguration.defaultConfiguration, "test1", "test2", "test3");
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
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), HTTPClientConfiguration.defaultConfiguration, "test1", "test2", "test3");
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
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), HTTPClientConfiguration.defaultConfiguration, "test1", "test2", "test3");
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
		client.close();
	}
		
	@Test
	public void testClose() throws Exception {
		// test close
		WebSocketClient client = new WebSocketClient();
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), HTTPClientConfiguration.defaultConfiguration, "test1", "test2", "test3");
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
		client.close();		
	}
	
	
	@Test
	public void testProxyConfigurations() throws Exception {
		WebSocketClient client = new WebSocketClient();
		HTTPClientConfiguration config = new HTTPClientConfiguration(HTTPClientConfiguration.defaultConfiguration);
		config.setProxySelector(null);
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), config, "test1", "test2", "test3");
		String selected = conn.blockResult(0);
		Assert.assertEquals(1, connected.get());
		Assert.assertEquals("test2", selected);
		client.close();
		
		// start proxy server
		TCPServer proxyServer = new TCPServer();
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger("test-proxy");
		logger.setLevel(Level.TRACE);
		ProxyHTTPRequestProcessor processor = new ProxyHTTPRequestProcessor(8192, logger);
		HTTPServerProtocol protocol = new HTTPServerProtocol(processor);
		proxyServer.setProtocol(protocol);
		SocketAddress proxyAddress = proxyServer.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);
		int proxyPort = ((InetSocketAddress)proxyAddress).getPort();
		config = new HTTPClientConfiguration(HTTPClientConfiguration.defaultConfiguration);
		config.setSSLContext(SSLContext.getDefault());
		processor.setHTTPForwardClientConfiguration(config);
		
		// test websocket through proxy
		config = new HTTPClientConfiguration(HTTPClientConfiguration.defaultConfiguration);
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

		// stop proxy
		proxyServer.close();
		
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
		String url = "http://localhost:" + ((InetSocketAddress)serverAddress).getPort() + "/";
		try {
			HTTPClientUtil.GET(url, 0,
				new MimeHeader(MimeMessage.CONNECTION, "Upgrade"),
				new MimeHeader("Upgrade", "websocket")
			).blockResult(0);
			throw new AssertionError("Error should be thrown");
		} catch (HTTPResponseError e) {
			Assert.assertEquals(400, e.getStatusCode());
		}
		try {
			HTTPClientUtil.GET(url, 0,
				new MimeHeader(MimeMessage.CONNECTION, "Upgrade"),
				new MimeHeader("Upgrade", "websocket"),
				new MimeHeader("Sec-WebSocket-Key", "hello")
			).blockResult(0);
			throw new AssertionError("Error should be thrown");
		} catch (HTTPResponseError e) {
			Assert.assertEquals(400, e.getStatusCode());
		}
		try {
			HTTPClientUtil.GET(url, 0,
				new MimeHeader(MimeMessage.CONNECTION, "Upgrade"),
				new MimeHeader("Upgrade", "websocket"),
				new MimeHeader("Sec-WebSocket-Key", "")
			).blockResult(0);
			throw new AssertionError("Error should be thrown");
		} catch (HTTPResponseError e) {
			Assert.assertEquals(400, e.getStatusCode());
		}
		try {
			HTTPClientUtil.GET(url, 0,
				new MimeHeader(MimeMessage.CONNECTION, "Upgrade"),
				new MimeHeader("Upgrade", "websocket"),
				new MimeHeader("Sec-WebSocket-Key", "hello"),
				new MimeHeader("Sec-WebSocket-Version", "51")
			).blockResult(0);
			throw new AssertionError("Error should be thrown");
		} catch (HTTPResponseError e) {
			Assert.assertEquals(400, e.getStatusCode());
		}
		try {
			HTTPClientUtil.GET(url, 0,
				new MimeHeader(MimeMessage.CONNECTION, "Upgrade"),
				new MimeHeader("Upgrade", "websocket"),
				new MimeHeader("Sec-WebSocket-Key", "hello"),
				new MimeHeader("Sec-WebSocket-Version", "")
			).blockResult(0);
			throw new AssertionError("Error should be thrown");
		} catch (HTTPResponseError e) {
			Assert.assertEquals(400, e.getStatusCode());
		}
	}
}
