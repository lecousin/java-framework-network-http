package net.lecousin.framework.network.http.websocket;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;

import net.lecousin.framework.collections.LinkedArrayList;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Readable.Seekable;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http.server.HTTPServerProtocol;
import net.lecousin.framework.network.server.TCPServerClient;

/** Dispatch messages received by the server. */
public class WebSocketDispatcher implements WebSocketServerProtocol.WebSocketMessageListener {

	/** Handle a list of connected clients. */
	public abstract static class WebSocketHandler {
		protected LinkedArrayList<TCPServerClient> connectedClients = new LinkedArrayList<>(10);
		
		/** Add a new connected client. */
		public void newClient(TCPServerClient client) {
			synchronized (connectedClients) { connectedClients.add(client); }
		}

		/** Remove a client. */
		public void clientClosed(TCPServerClient client) {
			synchronized (connectedClients) { connectedClients.remove(client); }
		}
		
		/** Return the protocol name. */
		public abstract String getProtocol();
		
		/** Process a text message from a client. */
		public abstract void processTextMessage(TCPServerClient client, String message);

		/** Process a binary message from a client. */
		public abstract void processBinaryMessage(TCPServerClient client, Seekable message);
		
		/** Send a text message to all connected clients. */
		public void sendTextMessage(String message) {
			ArrayList<TCPServerClient> clients;
			synchronized (connectedClients) {
				clients = new ArrayList<>(connectedClients);
			}
			WebSocketServerProtocol.sendTextMessage(clients, message);
		}

		/** Send a binary message to all connected clients. */
		public void sendBinaryMessage(IO.Readable message) {
			ArrayList<TCPServerClient> clients;
			synchronized (connectedClients) {
				clients = new ArrayList<>(connectedClients);
			}
			WebSocketServerProtocol.sendBinaryMessage(clients, message);
		}
	}
	
	/** Router of messages based on the path and requested protocols. */
	public static interface WebSocketRouter {
		/** Return the handler for the given path and protocols. */
		public WebSocketHandler getWebSocketHandler(TCPServerClient client, HTTPRequest request, String path, String[] protocols);
	}
	
	/** Basic implementation of WebSocketRouter that always return the same handler. */
	public static class SingleWebSocketHandler implements WebSocketRouter {
		/** Constructor. */
		public SingleWebSocketHandler(WebSocketHandler handler) {
			this.handler = handler;
		}
		
		protected WebSocketHandler handler;
		
		@Override
		public WebSocketHandler getWebSocketHandler(TCPServerClient client, HTTPRequest request, String path, String[] protocols) {
			return handler;
		}
	}
	
	private static final String WEB_SOCKET_HANDLER_ATTRIBUTE = "protocol.http.websocket.handler";
	
	/** Constructor. */
	public WebSocketDispatcher(WebSocketRouter router) {
		this.router = router;
	}
	
	private WebSocketRouter router;
	
	@Override
	public String onClientConnected(
		WebSocketServerProtocol websocket, TCPServerClient client, String[] requestedProtocols
	) throws HTTPResponseError {
		HTTPRequest request = (HTTPRequest)client.getAttribute(HTTPServerProtocol.REQUEST_ATTRIBUTE);
		String path = request.getPath();
		try { path = URLDecoder.decode(path, "UTF-8"); }
		catch (UnsupportedEncodingException e) {
			// should never happen
		}
		WebSocketHandler handler = router.getWebSocketHandler(client, request, path, requestedProtocols);
		if (handler == null)
			throw new HTTPResponseError(404, "WebSocket not found");
		handler.newClient(client);
		client.onclosed(() -> { handler.clientClosed(client); });
		client.setAttribute(WEB_SOCKET_HANDLER_ATTRIBUTE, handler);
		return handler.getProtocol();
	}
	
	@Override
	public void onTextMessage(WebSocketServerProtocol websocket, TCPServerClient client, String message) {
		WebSocketHandler handler = (WebSocketHandler)client.getAttribute(WEB_SOCKET_HANDLER_ATTRIBUTE);
		handler.processTextMessage(client, message);
	}
	
	@Override
	public void onBinaryMessage(WebSocketServerProtocol websocket, TCPServerClient client, Seekable message) {
		WebSocketHandler handler = (WebSocketHandler)client.getAttribute(WEB_SOCKET_HANDLER_ATTRIBUTE);
		handler.processBinaryMessage(client, message);
	}
	
}
