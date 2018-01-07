package net.lecousin.framework.network.http.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork.AsyncWorkListener;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.encoding.Base64;
import net.lecousin.framework.io.util.DataUtil;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.ServerProtocol;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/** Implements the WebSocket protocol on server side. */
public class WebSocketServerProtocol implements ServerProtocol {
	
	public static final Log logger = LogFactory.getLog(WebSocketServerProtocol.class);
	
	private static final String FIRST_DATA_FRAME_ATTRIBUTE = "protocol.http.websocket.first_dataframe";
	private static final String DATA_FRAME_ATTRIBUTE = "protocol.http.websocket.dataframe";
	
	/** Listener for web socket messages. */
	public static interface WebSocketMessageListener {
		/**
		 * Called when a client is opening a web socket connection.
		 * @param client the client
		 * @param requestedProtocols list of values found in Sec-WebSocket-Protocol, which can be empty
		 * @return the selected protocol, null to do not send a chosen protocol
		 */
		public String onClientConnected(WebSocketServerProtocol websocket, TCPServerClient client, String[] requestedProtocols)
		throws HTTPResponseError;
		
		/** Called when a new text message is received. */
		public void onTextMessage(WebSocketServerProtocol websocket, TCPServerClient client, String message);

		/** Called when a new binary message is received. */
		public void onBinaryMessage(WebSocketServerProtocol websocket, TCPServerClient client, IO.Readable.Seekable message);
	}
	
	/** Constructor. */
	public WebSocketServerProtocol(WebSocketMessageListener listener) {
		this.listener = listener;
	}
	
	private WebSocketMessageListener listener;
	
	@Override
	public void startProtocol(TCPServerClient client) {
		HTTPRequest request = (HTTPRequest)client.getAttribute(HTTPServerProtocol.REQUEST_ATTRIBUTE);
		String key = request.getMIME().getHeaderSingleValue("Sec-WebSocket-Key");
		String version = request.getMIME().getHeaderSingleValue("Sec-WebSocket-Version");
		if (key == null || key.trim().length() == 0) {
			HTTPServerProtocol.sendError(client, 400, "Missing Sec-WebSocket-Key header", request, true);
			return;
		}
		if (version == null || version.trim().length() == 0) {
			HTTPServerProtocol.sendError(client, 400, "Missing Sec-WebSocket-Version header", request, true);
			return;
		}
		if (!version.trim().equals("13")) {
			HTTPResponse resp = new HTTPResponse();
			resp.addHeaderValue("Sec-WebSocket-Version", "13");
			resp.setForceClose(true);
			HTTPServerProtocol.sendError(client, 400, "Unsupported WebSocket version", request, resp);
			return;
		}
		// TODO Sec-WebSocket-Protocol
		// TODO Sec-WebSocket-Extensions
		String[] requestedProtocols = request.getMIME().getHeaderCommaSeparatedValues("Sec-WebSocket-Protocol");
		if (requestedProtocols == null) requestedProtocols = new String[0];
		String protocol;
		try {
			protocol = listener.onClientConnected(this, client, requestedProtocols);
		} catch (HTTPResponseError e) {
			HTTPServerProtocol.sendError(client, e.getStatusCode(), e.getMessage(), request, true);
			return;
		}
		
		HTTPResponse resp = new HTTPResponse();
		resp.setStatus(101, "Switching Protocols");
		resp.addHeaderValue("Upgrade", "websocket");
		resp.addHeaderValue("Connection", "Upgrade");
		String acceptKey = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
		byte[] buf;
		try {
			buf = Base64.encodeBase64(MessageDigest.getInstance("SHA-1").digest(acceptKey.getBytes()));
		} catch (Exception e) {
			HTTPServerProtocol.sendError(client, 500, e.getMessage(), request, true);
			return;
		}
		acceptKey = new String(buf);
		resp.addHeaderValue("Sec-WebSocket-Accept", acceptKey);
		if (protocol != null)
			resp.addHeaderValue("Sec-WebSocket-Protocol", protocol);
		HTTPServerProtocol.sendResponse(client, request, resp);
		try { client.waitForData(0); }
		catch (ClosedChannelException e) { /* ignore */ }
	}
	
	@Override
	public boolean dataReceivedFromClient(TCPServerClient client, ByteBuffer data, Runnable onbufferavailable) {
		new Task.Cpu<Void,NoException>("Receiving WebSocket data from client", Task.PRIORITY_NORMAL) {
			@Override
			public Void run() {
				do {
					DataFrame frame = (DataFrame)client.getAttribute(DATA_FRAME_ATTRIBUTE);
					if (frame == null) {
						frame = new DataFrame();
						client.setAttribute(DATA_FRAME_ATTRIBUTE, frame);
						client.setAttribute(FIRST_DATA_FRAME_ATTRIBUTE, frame);
					}
					try {
						frame.read(client, data);
					} catch (IOException e) {
						logger.error("Error storing WebSocket data frame", e);
						client.close();
						return null;
					}
				} while (data.hasRemaining());
				onbufferavailable.run();
				try { client.waitForData(0); }
				catch (Exception e) { /* ignore */ }
				return null;
			}
		}.start();
		return false;
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
	
	private class DataFrame {
		DataFrame() {
			message = new IOInMemoryOrFile(32768, Task.PRIORITY_NORMAL, "WebSocket Data Frame");
		}
		
		DataFrame(DataFrame previous) {
			message = previous.message;
			messageType = previous.messageType;
		}
		
		int messageType = 0;
		int headerRead = 0;
		byte byte1;
		boolean maskPresent;
		int payloadLengthBits;
		long payloadLength;
		int maskRead = 0;
		byte[] maskValue = null;
		long messageRead = 0;
		IOInMemoryOrFile message;
		
		void read(TCPServerClient client, ByteBuffer data) throws IOException {
			if (headerRead == 0) {
				byte1 = data.get();
				headerRead++;
				if (messageType == 0)
					messageType = (byte1 & 0xF);
				if (!data.hasRemaining()) return;
			}
			if (headerRead == 1) {
				if (!data.hasRemaining()) return;
				byte b = data.get();
				headerRead++;
				maskPresent = (b & 0x80) != 0;
				payloadLength = (b & 0x7F);
				if (payloadLength == 126) {
					payloadLengthBits = 16;
					payloadLength = 0;
				} else if (payloadLength == 127) {
					payloadLengthBits = 64;
					payloadLength = 0;
				} else
					payloadLengthBits = 7;
			}
			while (payloadLengthBits == 16 && headerRead < 4) {
				if (!data.hasRemaining()) return;
				byte b = data.get();
				if (headerRead == 2)
					payloadLength = (b & 0xFF) << 8;
				else
					payloadLength |= (b & 0xFF);
				headerRead++;
			}
			while (payloadLengthBits == 64 && headerRead < 10) {
				if (!data.hasRemaining()) return;
				byte b = data.get();
				payloadLength |= (b & 0xFF) << (8 * (7 + 2 - headerRead));
				headerRead++;
			}
			if (maskPresent && maskValue == null)
				maskValue = new byte[4];
			while (maskPresent && maskRead < 4) {
				if (!data.hasRemaining()) return;
				maskValue[maskRead++] = data.get();
			}
			if (messageRead == payloadLength) {
				endOfFrame(client);
				return;
			}
			if (!data.hasRemaining()) return;
			int nb = data.remaining();
			if (messageRead + nb > payloadLength) nb = (int)(payloadLength - messageRead);
			byte[] buf = new byte[nb];
			data.get(buf);
			if (maskPresent) {
				for (int i = 0; i < nb; i++,messageRead++)
					buf[i] = (byte)((buf[i] & 0xFF) ^ (maskValue[(int)(messageRead % 4)] & 0xFF));
			} else
				messageRead += nb;
			message.writeSync(ByteBuffer.wrap(buf));
			if (messageRead == payloadLength)
				endOfFrame(client);
		}
		
		private void endOfFrame(TCPServerClient client) {
			if ((byte1 & 0x80) != 0) {
				// end of message => process it
				processMessage(client, message, messageType);
				client.removeAttribute(FIRST_DATA_FRAME_ATTRIBUTE);
				client.removeAttribute(DATA_FRAME_ATTRIBUTE);
				return;
			}
			// end of this frame, next frame coming
			// prepare next frame
			DataFrame frame = new DataFrame(this);
			client.setAttribute(DATA_FRAME_ATTRIBUTE, frame);
		}
	}
	
	@SuppressWarnings("resource")
	private void processMessage(TCPServerClient client, IOInMemoryOrFile message, int type) {
		if (type == 1) {
			// text message encoded with UTF-8
			byte[] buf = new byte[(int)message.getSizeSync()];
			message.readFullyAsync(0, ByteBuffer.wrap(buf)).listenInline(new AsyncWorkListener<Integer, IOException>() {
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
		if (type == 2) {
			// binary message
			message.seekSync(SeekType.FROM_BEGINNING, 0);
			try {
				listener.onBinaryMessage(WebSocketServerProtocol.this, client, message);
			} finally {
				message.closeAsync();
			}
			return;
		}
		if (type == 8) {
			// close
			sendMessage(client, 8, new ByteArrayIO(new byte[0], "Empty"), true);
			return;
		}
		if (type == 9) {
			// ping
			sendMessage(client, 10, message, false);
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
		@SuppressWarnings("resource")
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
			catch (Throwable e) { /* ignore */ }
		byte[] buffer = new byte[size >= 0 && size <= 128 * 1024 ? (int)size : 65536];
		sendMessagePart(clients, type, content, size, buffer, 0, closeAfter);
	}
	
	private static void sendMessagePart(
		List<TCPServerClient> clients, int type, IO.Readable content, long size, byte[] buffer, long pos, boolean closeAfter
	) {
		AsyncWorkListener<Integer, IOException> listener = new AsyncWorkListener<Integer, IOException>() {
			@Override
			public void ready(Integer nbRead) {
				boolean isLast;
				if (size >= 0)
					isLast = pos + nbRead.intValue() == size;
				else
					isLast = nbRead.intValue() < buffer.length;
				byte[] b = new byte[2 + (nbRead.intValue() <= 125 ? 0 : nbRead.intValue() <= 0xFFFF ? 2 : 8)];
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
				for (Iterator<TCPServerClient> it = clients.iterator(); it.hasNext(); ) {
					@SuppressWarnings("resource")
					TCPServerClient client = it.next();
					try { client.send(ByteBuffer.wrap(b), false); }
					catch (ClosedChannelException e) { it.remove(); }
				}
				if (clients.isEmpty()) {
					content.closeAsync();
					return;
				}
				for (Iterator<TCPServerClient> it = clients.iterator(); it.hasNext(); ) {
					@SuppressWarnings("resource")
					TCPServerClient client = it.next();
					try { client.send(ByteBuffer.wrap(buffer, 0, nbRead.intValue()), false); }
					catch (ClosedChannelException e) { it.remove(); }
				}
				if (clients.isEmpty()) {
					content.closeAsync();
					return;
				}
				if (!isLast) {
					sendMessagePart(clients, type, content, size, buffer, pos + nbRead.intValue(), closeAfter);
				} else {
					content.closeAsync();
					if (closeAfter) {
						for (TCPServerClient client : clients)
							client.close();
					}
				}
			}
			
			@Override
			public void cancelled(CancelException event) {
				content.closeAsync();
				for (TCPServerClient client : clients)
					client.close();
			}
			
			@Override
			public void error(IOException error) {
				content.closeAsync();
				for (TCPServerClient client : clients)
					client.close();
			}
		};
		if (size == 0)
			listener.ready(Integer.valueOf(0));
		else
			content.readFullyAsync(ByteBuffer.wrap(buffer)).listenInline(listener);
	}
	
}
