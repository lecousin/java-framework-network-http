package net.lecousin.framework.network.http.test;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.application.Artifact;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.application.Version;
import net.lecousin.framework.io.IO.Readable.Seekable;
import net.lecousin.framework.network.http.server.HTTPServerProtocol;
import net.lecousin.framework.network.http.server.WebSocketServerProtocol;
import net.lecousin.framework.network.http.server.WebSocketServerProtocol.WebSocketMessageListener;
import net.lecousin.framework.network.http.server.processor.StaticProcessor;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.server.TCPServerClient;

public class TestWebSocket {

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
	
}
