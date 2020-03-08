package net.lecousin.framework.network.websocket;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;

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
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.io.util.EmptyReadable;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http1.HTTP1RequestCommandProducer;
import net.lecousin.framework.network.http1.HTTP1ResponseStatusConsumer;
import net.lecousin.framework.network.mime.entity.DefaultMimeEntityFactory;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.text.ByteArrayStringIso8859Buffer;
import net.lecousin.framework.util.DebugUtil;

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
		ProxySelector proxySelector = config.getProxySelector();
		if (proxySelector == null)
			return directConnect(hostname, port, path, secure, config, protocols);
		StringBuilder url = new StringBuilder(128);
		url.append(secure ? "https://" : "http://");
		url.append(hostname).append(':').append(port);
		url.append(path);
		URI uri = null;
		Proxy proxy = null;
		try {
			uri = new URI(url.toString());
			List<Proxy> proxies = proxySelector.select(uri);
			for (Proxy p : proxies) {
				if (Proxy.Type.HTTP.equals(p.type())) {
					proxy = p;
					break;
				}
			}
		} catch (Exception e) {
			// ignore
		}
		if (proxy != null)
			return proxyConnect(proxy, hostname, port, path, secure, config, protocols);
		return directConnect(hostname, port, path, secure, config, protocols);
	}
	
	private AsyncSupplier<String, IOException> proxyConnect(
		Proxy proxy, String hostname, int port, String path, boolean secure, HTTPClientConfiguration config, String[] protocols
	) {
		// we have to create a HTTP tunnel with the proxy
		InetSocketAddress inet = (InetSocketAddress)proxy.address();
		inet = new InetSocketAddress(inet.getHostName(), inet.getPort());
		@SuppressWarnings("squid:S2095") // it is closed
		TCPClient tunnelClient = new TCPClient();
		Async<IOException> tunnelConnect =
			tunnelClient.connect(inet, config.getConnectionTimeout(), config.getSocketOptionsArray());
		AsyncSupplier<String, IOException> result = new AsyncSupplier<>();
		// prepare the CONNECT request
		HTTPRequest connectRequest = new HTTPRequest().setMethod(HTTPRequest.METHOD_CONNECT)
			.setEncodedPath(new ByteArrayStringIso8859Buffer(hostname + ":" + port));
		connectRequest.addHeader(HTTPConstants.Headers.Request.HOST, hostname + ":" + port);
		ByteArrayStringIso8859Buffer headers = new ByteArrayStringIso8859Buffer();
		headers.setNewArrayStringCapacity(4096);
		HTTP1RequestCommandProducer.generate(connectRequest, headers);
		headers.append("\r\n");
		connectRequest.getHeaders().generateString(headers);
		ByteBuffer[] buffers = headers.asByteBuffers();
		tunnelConnect.onDone(() -> tunnelClient.asConsumer(1, config.getSendTimeout()).push(Arrays.asList(buffers)).onDone(() -> {
			// once headers are sent, receive the response status line
			HTTPResponse response = new HTTPResponse();
			tunnelClient.getReceiver().consume(
				new HTTP1ResponseStatusConsumer(response)
				.convert(ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), IO::error),
				4096, config.getReceiveTimeout()).onDone(() -> {
				// status line received
				if (!response.isSuccess()) {
					result.error(new HTTPResponseError(response));
					return;
				}
				// read headers
				MimeHeaders responseHeaders = new MimeHeaders();
				response.setHeaders(responseHeaders);
				tunnelClient.getReceiver().consume(
					responseHeaders.createConsumer(config.getMaximumResponseHeadersLength())
					.convert(ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), IO::error),
					4096, config.getReceiveTimeout()).onDone(() -> {
						// headers received
						// tunnel connection established
						if (secure) {
							SSLContext ctx = config.getSSLContext();
							SSLClient ssl;
							if (ctx != null)
								ssl = new SSLClient(ctx);
							else
								try { ssl = new SSLClient(); }
								catch (Exception t) {
									result.error(new IOException("Error initializing SSL connection", t));
									return;
								}
							Async<IOException> ready = new Async<>();
							ssl.tunnelConnected(tunnelClient, ready, config.getReceiveTimeout());
							ready.thenStart(UPGRADE_TASK_DESCRIPTION, Task.Priority.NORMAL,
								() -> upgradeConnection(ssl, hostname, port, path, config, protocols, result),
								result);
						} else {
							Task.cpu(UPGRADE_TASK_DESCRIPTION, Task.Priority.NORMAL, t -> {
								upgradeConnection(tunnelClient, hostname, port, path, config, protocols, result);
								return null;
							}).start();
						}
					}, result);
			}, result);
		}, result), result);
		result.onErrorOrCancel(tunnelClient::close);
		return result;
	}
	
	@SuppressWarnings("squid:S2095") // client is closed
	private AsyncSupplier<String, IOException> directConnect(
		String hostname, int port, String path, boolean secure, HTTPClientConfiguration config, String[] protocols
	) {
		TCPClient client;
		if (secure) {
			SSLContext ctx = config.getSSLContext();
			if (ctx != null)
				client = new SSLClient(ctx);
			else
				try { client = new SSLClient(); }
				catch (Exception t) {
					return new AsyncSupplier<>(null, new IOException("Error initializing SSL connection", t));
				}
		} else {
			client = new TCPClient();
		}
		AsyncSupplier<String, IOException> result = new AsyncSupplier<>();
		client.connect(new InetSocketAddress(hostname, port), config.getConnectionTimeout(), config.getSocketOptionsArray())
			.thenStart(UPGRADE_TASK_DESCRIPTION, Task.Priority.NORMAL,
				() -> upgradeConnection(client, hostname, port, path, config, protocols, result), result);
		result.onErrorOrCancel(client::close);
		return result;
	}

	@SuppressWarnings("squid:S2119") // we save the Random
	private void upgradeConnection(
		TCPClient client, String hostname, int port, String path, HTTPClientConfiguration config,
		String[] protocols, AsyncSupplier<String, IOException> result
	) {
		@SuppressWarnings("resource")
		HTTPClient httpClient = new HTTPClient(client, hostname, port, config);
		HTTPRequest request = new HTTPRequest().get(path);
		// Upgrade connection
		request.addHeader(HTTPConstants.Headers.Request.CONNECTION, HTTPConstants.Headers.Request.CONNECTION_VALUE_UPGRADE);
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
		Async<IOException> send = httpClient.sendRequest(request);
		
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
		
		send.onDone(() -> httpClient.receiveResponse(null, response -> {
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
		}, DefaultMimeEntityFactory.getInstance()), result);
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
				IAsync<IOException> send = conn.send(ByteBuffer.wrap(buffer, 0, nbRead.intValue()), 30000);
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
