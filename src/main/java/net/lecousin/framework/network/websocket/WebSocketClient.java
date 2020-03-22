package net.lecousin.framework.network.websocket;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Random;
import java.util.function.Consumer;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.AsyncSupplier.Listener;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.encoding.Base64Encoding;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.util.EmptyReadable;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientResponse;
import net.lecousin.framework.network.http1.client.HTTP1ClientUtil;
import net.lecousin.framework.network.mime.entity.DefaultMimeEntityFactory;
import net.lecousin.framework.util.DebugUtil;
import net.lecousin.framework.util.Triple;

/** Client for web socket protocol. */
public class WebSocketClient implements Closeable {

	private TCPClient conn;
	private Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(WebSocketClient.class);
	
	private static final String UPGRADE_TASK_DESCRIPTION = "Upgrade HTTP connection for WebSocket protocol";
	
	@Override
	public void close() {
		if (conn != null)
			conn.close();
	}
	
	public boolean isClosed() {
		return conn == null || conn.isClosed();
	}
	
	/** Connect and return the selected protocol. */
	public AsyncSupplier<String, IOException> connect(URI uri, HTTPClientConfiguration config, String... protocols) {
		String protocol = uri.getScheme();
		if (protocol == null) protocol = "ws";
		else protocol = protocol.toLowerCase();
		boolean secure = protocol.equals("wss");
		int port = uri.getPort();
		if (port <= 0) port = secure ? HTTPConstants.DEFAULT_HTTPS_PORT : HTTPConstants.DEFAULT_HTTP_PORT;
		return connect(uri.getHost(), port, uri.getPath(), secure, config, protocols);
	}
	
	/** Connect and return the selected protocol. */
	public AsyncSupplier<String, IOException> connect(
		String hostname, int port, String path, boolean secure, HTTPClientConfiguration config, String... protocols
	) {
		Triple<? extends TCPClient, IAsync<IOException>, Boolean> connect;
		try { connect = HTTP1ClientUtil.openConnection(hostname, port, secure, path, config, logger); }
		catch (URISyntaxException e) {
			return new AsyncSupplier<>(null, IO.error(e));
		}
		AsyncSupplier<String, IOException> result = new AsyncSupplier<>();
		connect.getValue2().thenStart(UPGRADE_TASK_DESCRIPTION, Task.Priority.NORMAL,
			() -> upgradeConnection(connect.getValue1(), hostname, port, secure, connect.getValue3().booleanValue(),
				path, config, protocols, result), result);
		result.onErrorOrCancel(() -> connect.getValue1().close());
		return result;

	}
	
	@SuppressWarnings({
		"java:S2119", // we save the Random
		"java:S107" // number of parameters
	})
	private void upgradeConnection(
		TCPClient client, String hostname, int port, boolean secure, boolean isUsingProxy, String path, HTTPClientConfiguration config,
		String[] protocols, AsyncSupplier<String, IOException> result
	) {
		if (logger.debug())
			logger.debug("Connected to HTTP server " + hostname + ", upgrading protocol to WebSocket");
		HTTPClientRequest request = new HTTPClientRequest(hostname, port, secure).get(path);
		// Upgrade connection
		request.addHeader(HTTPConstants.Headers.CONNECTION, HTTPConstants.Headers.CONNECTION_VALUE_UPGRADE);
		request.addHeader(HTTPConstants.Headers.Request.UPGRADE, "websocket");
		// Generate random key
		Random rand = LCCore.getApplication().getInstance(Random.class);
		if (rand == null) {
			rand = new Random();
			LCCore.getApplication().setInstance(Random.class, rand);
		}
		byte[] keyBytes = new byte[16];
		rand.nextBytes(keyBytes);
		String key = new String(Base64Encoding.instance.encode(keyBytes), StandardCharsets.US_ASCII);
		request.addHeader("Sec-WebSocket-Key", key);
		// protocols
		StringBuilder protocolList = new StringBuilder();
		for (String p : protocols) {
			if (protocolList.length() > 0) protocolList.append(", ");
			protocolList.append(p);
		}
		request.addHeader("Sec-WebSocket-Protocol", protocolList.toString());
		// set version
		request.addHeader("Sec-WebSocket-Version", "13");
		
		// send HTTP request
		HTTPClientResponse response = new HTTPClientResponse();
		Async<IOException> send = HTTP1ClientUtil.send(client, new Async<>(true), request, response, config, logger);
		
		// calculate the expected accept key
		String acceptKey = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
		byte[] buf;
		try {
			buf = Base64Encoding.instance.encode(
				MessageDigest.getInstance("SHA-1").digest(acceptKey.getBytes(StandardCharsets.US_ASCII)));
		} catch (Exception e) {
			client.close();
			result.error(new IOException("Unable to encode key", e));
			return;
		}
		String expectedAcceptKey = new String(buf, StandardCharsets.US_ASCII);
		
		send.onDone(() -> HTTP1ClientUtil.receiveResponse(client, request, response, isUsingProxy, null, resp -> {
			if (logger.debug())
				logger.debug("Response received to upgrade request: " + response.getStatusCode());
			if (response.getStatusCode() != 101) {
				result.error(new IOException("Server does not support websocket on this address, response received is "
					+ response.getStatusCode()));
				return Boolean.TRUE;
			}
			String accept = response.getHeaders().getFirstRawValue("Sec-WebSocket-Accept");
			if (accept == null) {
				result.error(new IOException("The server did not return the accept key"));
				return Boolean.TRUE;
			}
			if (!expectedAcceptKey.equals(accept)) {
				result.error(new IOException("The server returned an invalid accept key"));
				return Boolean.TRUE;
			}
			String protocol = response.getHeaders().getFirstRawValue("Sec-WebSocket-Protocol");
			if (protocol == null) {
				result.error(new IOException("The server did not return the selected protocol"));
				return Boolean.TRUE;
			}
			if (logger.trace())
				logger.trace("HTTP protocol upgraded to WebSocket for " + client);
			conn = client;
			result.unblockSuccess(protocol);
			return Boolean.FALSE;
		}, DefaultMimeEntityFactory.getInstance(), config, logger), result);
	}

	private WebSocketDataFrame currentFrame = null;
	
	/** Start to listen to messages. This method must be called only once. */
	public void onMessage(Consumer<WebSocketDataFrame> listener) {
		conn.getReceiver().readForEver(4096, 0, data -> {
			if (logger.trace()) {
				if (data != null) {
					StringBuilder s = new StringBuilder("New data from server:\r\n");
					DebugUtil.dumpHex(s, data);
					logger.trace(s.toString());
				} else {
					logger.trace("End of data received from server");
				}
			}
			if (data == null) {
				close();
				return;
			}
			if (currentFrame == null)
				currentFrame = new WebSocketDataFrame();
			try {
				if (!currentFrame.read(data))
					return;
				WebSocketDataFrame frame = currentFrame;
				currentFrame = null;
				if (logger.trace())
					logger.trace("Received message of type " + frame.getMessageType() + " from " + conn);
				listener.accept(frame);
			} catch (Exception t) {
				LCCore.getApplication().getDefaultLogger().error("Error reading web-socket frame", t);
				return;
			}
		}, true);
	}
	
	/** Send a text message to a list of clients. */
	public Async<IOException> sendTextMessage(String message) {
		byte[] text = message.getBytes(StandardCharsets.UTF_8);
		ByteArrayIO io = new ByteArrayIO(text, "WebSocket message to send");
		return sendMessage(WebSocketDataFrame.TYPE_TEXT, io);
	}
	
	/** Send a binary message. */
	public Async<IOException> sendBinaryMessage(IO.Readable message) {
		return sendMessage(WebSocketDataFrame.TYPE_BINARY, message);
	}
	
	/** Send a ping empty message. */
	public Async<IOException> sendPing() {
		return sendPing(new EmptyReadable("Empty", Task.Priority.NORMAL));
	}
	
	/** Send a ping message. */
	public Async<IOException> sendPing(IO.Readable message) {
		return sendMessage(WebSocketDataFrame.TYPE_PING, message);
	}
	
	/** Send a close message. */
	public Async<IOException> sendClose() {
		return sendMessage(WebSocketDataFrame.TYPE_CLOSE, new EmptyReadable("Empty", Task.Priority.NORMAL));
	}
	
	/** Send a message to a client. */
	public Async<IOException> sendMessage(int type, IO.Readable content) {
		if (logger.trace())
			logger.trace("Send message of type " + type + " to " + conn);
		long size = -1;
		if (content instanceof IO.KnownSize)
			try { size = ((IO.KnownSize)content).getSizeSync(); }
			catch (Exception e) { /* ignore */ }
		byte[] buffer = new byte[size >= 0 && size <= 128 * 1024 ? (int)size : 65536];
		Async<IOException> ondone = new Async<>();
		sendMessagePart(type, content, size, buffer, 0, ondone);
		return ondone;
	}
	
	private void sendMessagePart(int type, IO.Readable content, long size, byte[] buffer, long pos, Async<IOException> ondone) {
		Listener<Integer, IOException> listener = new Listener<Integer, IOException>() {
			@Override
			public void ready(Integer nbRead) {
				boolean isLast;
				if (size >= 0)
					isLast = pos + nbRead.intValue() == size;
				else
					isLast = nbRead.intValue() < buffer.length;
				byte[] b = WebSocketDataFrame.createMessageStart(isLast, pos, nbRead.intValue(), type);
				conn.send(ByteBuffer.wrap(b), 30000);
				IAsync<IOException> send = conn.send(ByteBuffer.wrap(buffer, 0, nbRead.intValue()).asReadOnlyBuffer(), 30000);
				if (!isLast) {
					send.thenStart("Sending WebSocket message", Task.Priority.NORMAL,
						() -> sendMessagePart(type, content, size, buffer, pos + nbRead.intValue(), ondone), ondone);
				} else {
					content.closeAsync();
					send.onDone(ondone);
				}
			}
			
			@Override
			public void cancelled(CancelException event) {
				content.closeAsync();
				ondone.cancel(event);
			}
			
			@Override
			public void error(IOException error) {
				content.closeAsync();
				ondone.error(error);
			}
		};
		if (size == 0)
			listener.ready(Integer.valueOf(0));
		else
			content.readFullyAsync(ByteBuffer.wrap(buffer)).listen(listener);
	}
	
}
