package net.lecousin.framework.network.http.test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.application.Artifact;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.application.Version;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.io.IO.Readable.Seekable;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.mutable.Mutable;
import net.lecousin.framework.mutable.MutableInteger;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.server.HTTPServerProtocol;
import net.lecousin.framework.network.http.server.processor.StaticProcessor;
import net.lecousin.framework.network.http.websocket.WebSocketClient;
import net.lecousin.framework.network.http.websocket.WebSocketDataFrame;
import net.lecousin.framework.network.http.websocket.WebSocketServerProtocol;
import net.lecousin.framework.network.http.websocket.WebSocketServerProtocol.WebSocketMessageListener;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.server.TCPServerClient;

import org.junit.Assert;
import org.junit.Test;

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
			http_server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 1111), 10);
			Thread.sleep(5*60*1000);
			LCCore.stop(true);
		} catch (Throwable t) {
			t.printStackTrace(System.err);
		}
	}
	
	@SuppressWarnings("resource")
	@Test
	public void test() throws Exception {
		TCPServer server = new TCPServer();
		HTTPServerProtocol protocol = new HTTPServerProtocol(new StaticProcessor("net/lecousin/framework/network/http/test/websocket"));
		MutableInteger connected = new MutableInteger(0);
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
		server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 1111), 10);

		WebSocketClient client;

		// try a wrong protocol
		Assert.assertEquals(0, connected.get());
		client = new WebSocketClient();
		AsyncWork<String, IOException> conn = client.connect(new URI("ws://localhost:1111/"), HTTPClientConfiguration.defaultConfiguration, "hello", "world");
		conn.block(0);
		client.close();
		Assert.assertEquals(1, connected.get());
		Assert.assertFalse(conn.isSuccessful());

		// try a correct protocol
		client = new WebSocketClient();
		conn = client.connect(new URI("ws://localhost:1111/"), HTTPClientConfiguration.defaultConfiguration, "test1", "test2", "test3");
		String selected = conn.blockResult(0);
		Assert.assertEquals(2, connected.get());
		Assert.assertEquals("test2", selected);
		client.close();
		
		// try text messages
		client = new WebSocketClient();
		conn = client.connect(new URI("ws://localhost:1111/"), HTTPClientConfiguration.defaultConfiguration, "test1", "test2", "test3");
		selected = conn.blockResult(0);
		Assert.assertEquals(3, connected.get());
		Assert.assertEquals("test2", selected);
		Mutable<String> received = new Mutable<>(null);
		Mutable<SynchronizationPoint<Exception>> sp = new Mutable<>(new SynchronizationPoint<>());
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
		sp.set(new SynchronizationPoint<>());
		client.sendTextMessage("World").blockThrow(0);
		sp.get().blockThrow(5000);
		Assert.assertEquals("Hello World!", received.get());
		client.close();
		
		// try binary message
		client = new WebSocketClient();
		conn = client.connect(new URI("ws://localhost:1111/"), HTTPClientConfiguration.defaultConfiguration, "test1", "test2", "test3");
		selected = conn.blockResult(0);
		Assert.assertEquals(4, connected.get());
		Assert.assertEquals("test2", selected);
		Mutable<ByteBuffer> binary = new Mutable<>(null);
		sp.set(new SynchronizationPoint<>());
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
		
		// test ping
		client = new WebSocketClient();
		conn = client.connect(new URI("ws://localhost:1111/"), HTTPClientConfiguration.defaultConfiguration, "test1", "test2", "test3");
		selected = conn.blockResult(0);
		Assert.assertEquals(5, connected.get());
		Assert.assertEquals("test2", selected);
		sp.set(new SynchronizationPoint<>());
		client.onMessage((frame) -> {
			if (frame.getMessageType() != WebSocketDataFrame.TYPE_PONG)
				sp.get().error(new Exception("Unexpected message: " + frame.getMessageType()));
			else
				sp.get().unblock();
		});
		client.sendPing().blockThrow(0);
		sp.get().blockThrow(5000);
		client.close();
		
		// test close
		client = new WebSocketClient();
		conn = client.connect(new URI("ws://localhost:1111/"), HTTPClientConfiguration.defaultConfiguration, "test1", "test2", "test3");
		selected = conn.blockResult(0);
		Assert.assertEquals(6, connected.get());
		Assert.assertEquals("test2", selected);
		sp.set(new SynchronizationPoint<>());
		client.onMessage((frame) -> {
			if (frame.getMessageType() != WebSocketDataFrame.TYPE_CLOSE)
				sp.get().error(new Exception("Unexpected message: " + frame.getMessageType()));
			else
				sp.get().unblock();
		});
		client.sendClose().blockThrow(0);
		sp.get().blockThrow(5000);
		client.close();
		
		server.close();
	}
	
}
