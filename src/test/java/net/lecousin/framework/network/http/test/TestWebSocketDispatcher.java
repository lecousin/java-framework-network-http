package net.lecousin.framework.network.http.test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.io.IO.Readable.Seekable;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.mutable.Mutable;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.server.HTTPServerProtocol;
import net.lecousin.framework.network.http.server.processor.StaticProcessor;
import net.lecousin.framework.network.http.websocket.WebSocketClient;
import net.lecousin.framework.network.http.websocket.WebSocketDataFrame;
import net.lecousin.framework.network.http.websocket.WebSocketDispatcher;
import net.lecousin.framework.network.http.websocket.WebSocketDispatcher.SingleWebSocketHandler;
import net.lecousin.framework.network.http.websocket.WebSocketDispatcher.WebSocketHandler;
import net.lecousin.framework.network.http.websocket.WebSocketServerProtocol;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.server.TCPServerClient;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.BlockJUnit4ClassRunner;

@RunWith(BlockJUnit4ClassRunner.class)
public class TestWebSocketDispatcher extends AbstractHTTPTest {

	private static class Handler extends WebSocketHandler {

		@Override
		public String getProtocol() {
			return "test_proto";
		}

		@Override
		public void processTextMessage(TCPServerClient client, String message) {
			WebSocketServerProtocol.sendTextMessage(client, "Hello World!");
		}

		@Override
		public void processBinaryMessage(TCPServerClient client, Seekable message) {
			WebSocketServerProtocol.sendTextMessage(client, "Hello Binary!");
		}
		
		public int getNbClients() {
			return connectedClients.size();
		}
		
	}
	
	private TCPServer server;
	private SocketAddress serverAddress;
	private Handler handler;
	
	@Before
	public void startServer() throws Exception {
		server = new TCPServer();
		HTTPServerProtocol protocol = new HTTPServerProtocol(new StaticProcessor("net/lecousin/framework/network/http/test/websocket"));
		handler = new Handler();
		WebSocketServerProtocol wsProtocol = new WebSocketServerProtocol(new WebSocketDispatcher(new SingleWebSocketHandler(handler)));
		protocol.enableWebSocket(wsProtocol);
		server.setProtocol(protocol);
		serverAddress = server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 10).blockResult(0);
	}
	
	@After
	public void stopServer() {
		server.close();
	}
	
	private URI getServerURI() throws URISyntaxException {
		return new URI("ws://localhost:" + ((InetSocketAddress)serverAddress).getPort() + "/");
	}
	
	@Test
	public void testTextMessages() throws Exception {
		// try text messages
		Assert.assertEquals(0, handler.getNbClients());
		WebSocketClient client = new WebSocketClient();
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), HTTPClientConfiguration.defaultConfiguration, "test_proto");
		String selected = conn.blockResult(0);
		Assert.assertEquals(1, handler.getNbClients());
		Assert.assertEquals("test_proto", selected);
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
		Assert.assertEquals("Hello World!", received.get());
		client.close();
		for (int i = 0; i < 100 && handler.getNbClients() > 0; ++i)
			Thread.sleep(25);
		Assert.assertEquals(0, handler.getNbClients());
	}
	
	@Test
	public void testBinaryMessages() throws Exception {
		// try text messages
		Assert.assertEquals(0, handler.getNbClients());
		WebSocketClient client = new WebSocketClient();
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), HTTPClientConfiguration.defaultConfiguration, "test_proto");
		String selected = conn.blockResult(0);
		Assert.assertEquals(1, handler.getNbClients());
		Assert.assertEquals("test_proto", selected);
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
		client.sendBinaryMessage(new ByteArrayIO(new byte[] { 1, 2, 3 }, "test")).blockThrow(0);
		sp.get().blockThrow(5000);
		Assert.assertEquals("Hello Binary!", received.get());
		client.close();
		for (int i = 0; i < 100 && handler.getNbClients() > 0; ++i)
			Thread.sleep(25);
		Assert.assertEquals(0, handler.getNbClients());
	}
		
	@Test
	public void testPing() throws Exception {
		// test ping
		WebSocketClient client = new WebSocketClient();
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), HTTPClientConfiguration.defaultConfiguration, "test_proto");
		String selected = conn.blockResult(0);
		Assert.assertEquals(1, handler.getNbClients());
		Assert.assertEquals("test_proto", selected);
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
		AsyncSupplier<String, IOException> conn = client.connect(getServerURI(), HTTPClientConfiguration.defaultConfiguration, "test_proto");
		String selected = conn.blockResult(0);
		Assert.assertEquals(1, handler.getNbClients());
		Assert.assertEquals("test_proto", selected);
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
	
}
