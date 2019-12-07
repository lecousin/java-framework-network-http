package net.lecousin.framework.network.http.websocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.AsyncSupplier.Listener;
import net.lecousin.framework.concurrent.async.CancelException;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.encoding.Base64;
import net.lecousin.framework.io.util.DataUtil;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http.server.HTTPServerProtocol;
import net.lecousin.framework.network.http.server.HTTPServerResponse;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.ServerProtocol;

/** Implements the WebSocket protocol on server side. */
public class WebSocketServerProtocol implements ServerProtocol {
	
	private static final String DATA_FRAME_ATTRIBUTE = "protocol.http.websocket.dataframe";
	
	/** Listener for web socket messages. */
	public static interface WebSocketMessageListener {
		/**
		 * Called when a client is opening a web socket connection.
		 * @param client the client
		 * @param requestedProtocols list of values found in Sec-WebSocket-Protocol, which can be empty
		 * @return the selected protocol, null to do not send a chosen protocol
		 */
		String onClientConnected(WebSocketServerProtocol websocket, TCPServerClient client, String[] requestedProtocols)
		throws HTTPResponseError;
		
		/** Called when a new text message is received. */
		void onTextMessage(WebSocketServerProtocol websocket, TCPServerClient client, String message);

		/** Called when a new binary message is received. */
		void onBinaryMessage(WebSocketServerProtocol websocket, TCPServerClient client, IO.Readable.Seekable message);
	}
	
	/** Constructor. */
	public WebSocketServerProtocol(WebSocketMessageListener listener) {
		this.listener = listener;
		this.logger = LCCore.getApplication().getLoggerFactory().getLogger(WebSocketServerProtocol.class);
	}
	
	private Logger logger;
	private WebSocketMessageListener listener;
	
	@Override
	public void startProtocol(TCPServerClient client) {
		HTTPRequest request = (HTTPRequest)client.getAttribute(HTTPServerProtocol.REQUEST_ATTRIBUTE);
		String key = request.getMIME().getFirstHeaderRawValue("Sec-WebSocket-Key");
		String version = request.getMIME().getFirstHeaderRawValue("Sec-WebSocket-Version");
		if (key == null || key.trim().length() == 0) {
			HTTPServerResponse response = new HTTPServerResponse();
			response.setForceClose(true);
			HTTPServerProtocol.sendError(client, 400, "Missing Sec-WebSocket-Key header", request, response);
			return;
		}
		if (version == null || version.trim().length() == 0) {
			HTTPServerResponse response = new HTTPServerResponse();
			response.setForceClose(true);
			HTTPServerProtocol.sendError(client, 400, "Missing Sec-WebSocket-Version header", request, response);
			return;
		}
		if (!version.trim().equals("13")) {
			HTTPServerResponse resp = new HTTPServerResponse();
			resp.addHeaderRaw("Sec-WebSocket-Version", "13");
			resp.setForceClose(true);
			HTTPServerProtocol.sendError(client, 400, "Unsupported WebSocket version", request, resp);
			return;
		}
		// TODO Sec-WebSocket-Protocol
		// TODO Sec-WebSocket-Extensions
		String s = request.getMIME().getFirstHeaderRawValue("Sec-WebSocket-Protocol");
		String[] requestedProtocols = s != null ? s.split(",") : new String[0];
		for (int i = 0; i < requestedProtocols.length; ++i)
			requestedProtocols[i] = requestedProtocols[i].trim();
		String protocol;
		try {
			protocol = listener.onClientConnected(this, client, requestedProtocols);
		} catch (HTTPResponseError e) {
			HTTPServerResponse response = new HTTPServerResponse();
			response.setForceClose(true);
			HTTPServerProtocol.sendError(client, e.getStatusCode(), e.getMessage(), request, response);
			return;
		}
		
		HTTPServerResponse resp = new HTTPServerResponse();
		resp.setStatus(101, "Switching Protocols");
		resp.addHeaderRaw("Upgrade", "websocket");
		resp.addHeaderRaw("Connection", "Upgrade");
		String acceptKey = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
		byte[] buf;
		try {
			buf = Base64.encodeBase64(MessageDigest.getInstance("SHA-1").digest(acceptKey.getBytes(StandardCharsets.US_ASCII)));
		} catch (Exception e) {
			resp.setForceClose(true);
			HTTPServerProtocol.sendError(client, 500, e.getMessage(), request, resp);
			return;
		}
		acceptKey = new String(buf, StandardCharsets.US_ASCII);
		resp.addHeaderRaw("Sec-WebSocket-Accept", acceptKey);
		if (protocol != null)
			resp.addHeaderRaw("Sec-WebSocket-Protocol", protocol);
		HTTPServerProtocol.sendResponse(client, request, resp);
		try { client.waitForData(0); }
		catch (ClosedChannelException e) { /* ignore */ }
	}
	
	@Override
	public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data, Runnable onbufferavailable) {
		do {
			WebSocketDataFrame frame = (WebSocketDataFrame)client.getAttribute(DATA_FRAME_ATTRIBUTE);
			if (frame == null) {
				frame = new WebSocketDataFrame();
				client.setAttribute(DATA_FRAME_ATTRIBUTE, frame);
			}
			try {
				if (frame.read(data)) {
					processMessage(client, frame.getMessage(), frame.getMessageType());
					client.removeAttribute(DATA_FRAME_ATTRIBUTE);
				}
			} catch (IOException e) {
				logger.error("Error storing WebSocket data frame", e);
				client.close();
				return;
			}
		} while (data.hasRemaining());
		onbufferavailable.run();
		try { client.waitForData(0); }
		catch (Exception e) { /* ignore */ }
	}
	
	@Override
	public int getInputBufferSize() {
		return 8192;
	}
	
	@Override
	public LinkedList<ByteBuffer> prepareDataToSend(TCPServerClient client, ByteBuffer data) {
		LinkedList<ByteBuffer> list = new LinkedList<>();
		list.add(data);
		return list;
	}
	
	private void processMessage(TCPServerClient client, IOInMemoryOrFile message, int type) {
		if (type == WebSocketDataFrame.TYPE_TEXT) {
			// text message encoded with UTF-8
			byte[] buf = new byte[(int)message.getSizeSync()];
			message.readFullyAsync(0, ByteBuffer.wrap(buf)).listen(new Listener<Integer, IOException>() {
				@Override
				public void ready(Integer result) {
					new Task.Cpu<Void,NoException>("Processing WebSocket text message", Task.PRIORITY_NORMAL) {
						@Override
						public Void run() {
							try {
								listener.onTextMessage(
									WebSocketServerProtocol.this, client,
									new String(buf, StandardCharsets.UTF_8));
							} finally {
								message.closeAsync();
							}
							return null;
						}
					}.start();
				}
				
				@Override
				public void cancelled(CancelException event) {
					message.closeAsync();
				}
				
				@Override
				public void error(IOException error) {
					message.closeAsync();
				}
			});
			return;
		}
		if (type == WebSocketDataFrame.TYPE_BINARY) {
			// binary message
			message.seekSync(SeekType.FROM_BEGINNING, 0);
			try {
				listener.onBinaryMessage(WebSocketServerProtocol.this, client, message);
			} finally {
				message.closeAsync();
			}
			return;
		}
		if (type == WebSocketDataFrame.TYPE_CLOSE) {
			// close
			sendMessage(client, WebSocketDataFrame.TYPE_CLOSE, new ByteArrayIO(new byte[0], "Empty"), true);
			return;
		}
		if (type == WebSocketDataFrame.TYPE_PING) {
			// ping
			sendMessage(client, WebSocketDataFrame.TYPE_PONG, message, false);
			return;
		}
		logger.error("Unknown message type received: opcode = " + type);
		message.closeAsync();
	}
	
	/** Send a text message to a client. */
	public static void sendTextMessage(TCPServerClient client, String message) {
		sendTextMessage(Collections.singletonList(client), message);
	}
	
	/** Send a text message to a list of clients. */
	public static void sendTextMessage(List<TCPServerClient> clients, String message) {
		byte[] text = message.getBytes(StandardCharsets.UTF_8);
		ByteArrayIO io = new ByteArrayIO(text, "WebSocket message to send");
		sendMessage(clients, 1, io, false);
	}
	
	/** Send a binary message to a client. */
	public static void sendBinaryMessage(TCPServerClient client, IO.Readable message) {
		sendBinaryMessage(Collections.singletonList(client), message);
	}

	/** Send a binary message to a list of clients. */
	public static void sendBinaryMessage(List<TCPServerClient> clients, IO.Readable message) {
		sendMessage(clients, 2, message, false);
	}
	
	/** Send a message to a client. */
	public static void sendMessage(TCPServerClient client, int type, IO.Readable content, boolean closeAfter) {
		sendMessage(Collections.singletonList(client), type, content, closeAfter);
	}
	
	/** Send a message to a list of clients. */
	public static void sendMessage(List<TCPServerClient> clients, int type, IO.Readable content, boolean closeAfter) {
		long size = -1;
		if (content instanceof IO.KnownSize)
			try { size = ((IO.KnownSize)content).getSizeSync(); }
			catch (Exception e) { /* ignore */ }
		byte[] buffer = new byte[size >= 0 && size <= 128 * 1024 ? (int)size : 65536];
		sendMessagePart(clients, type, content, size, buffer, 0, closeAfter);
	}
	
	@SuppressWarnings("squid:S4276") // cannot use IntConsumer
	private static void sendMessagePart(
		List<TCPServerClient> clients, int type, IO.Readable content, long size, byte[] buffer, long pos, boolean closeAfter
	) {
		Consumer<Integer> listener = nbRead -> {
			boolean isLast;
			if (size >= 0)
				isLast = pos + nbRead.intValue() == size;
			else
				isLast = nbRead.intValue() < buffer.length;
			int bufLen = 2;
			if (nbRead.intValue() > 125)
				bufLen += nbRead.intValue() <= 0xFFFF ? 2 : 8;
			byte[] b = new byte[bufLen];
			b[0] = (byte)((isLast ? 0x80 : 0) + (pos == 0 ? type : 0));
			if (nbRead.intValue() <= 125) {
				b[1] = (byte)nbRead.intValue();
			} else if (nbRead.intValue() <= 0xFFFF) {
				b[1] = (byte)126;
				DataUtil.writeUnsignedShortBigEndian(b, 2, nbRead.intValue());
			} else {
				b[1] = (byte)127;
				DataUtil.writeLongBigEndian(b, 2, nbRead.intValue());
			}
			sendToClients(b, bufLen, clients);
			sendToClients(buffer, nbRead.intValue(), clients);
			if (clients.isEmpty()) {
				content.closeAsync();
				return;
			}
			if (!isLast) {
				sendMessagePart(clients, type, content, size, buffer, pos + nbRead.intValue(), closeAfter);
			} else {
				content.closeAsync();
				if (closeAfter) clients.forEach(TCPServerClient::close);
			}
		};
		if (size == 0) {
			listener.accept(Integer.valueOf(0));
			return;
		}
		Runnable close = () -> {
			content.closeAsync();
			for (TCPServerClient client : clients)
				client.close();
		};
		content.readFullyAsync(ByteBuffer.wrap(buffer)).onDone(listener, e -> close.run(), e -> close.run());
	}
	
	private static void sendToClients(byte[] b, int len, List<TCPServerClient> clients) {
		for (Iterator<TCPServerClient> it = clients.iterator(); it.hasNext(); ) {
			TCPServerClient client = it.next();
			try { client.send(ByteBuffer.wrap(b, 0, len), false); }
			catch (ClosedChannelException e) { it.remove(); }
		}
	}
	
}
