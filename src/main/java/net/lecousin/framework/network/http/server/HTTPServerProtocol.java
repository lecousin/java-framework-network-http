package net.lecousin.framework.network.http.server;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.AsyncWork.AsyncWorkListener;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.JoinPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.SubIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.buffering.SimpleBufferedReadable;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.math.RangeLong;
import net.lecousin.framework.mutable.Mutable;
import net.lecousin.framework.mutable.MutableLong;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPRequest.Protocol;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.LibraryVersion;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http.websocket.WebSocketServerProtocol;
import net.lecousin.framework.network.mime.MimeHeader;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.MimeUtil;
import net.lecousin.framework.network.mime.entity.MultipartEntity;
import net.lecousin.framework.network.mime.transfer.ChunkedTransfer;
import net.lecousin.framework.network.mime.transfer.TransferEncodingFactory;
import net.lecousin.framework.network.mime.transfer.TransferReceiver;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.ServerProtocol;
import net.lecousin.framework.util.UnprotectedString;
import net.lecousin.framework.util.UnprotectedStringBuffer;

/** Implements the HTTP protocol on server side. */
public class HTTPServerProtocol implements ServerProtocol {

	public static final String REQUEST_ATTRIBUTE = "protocol.http.request";
	private static final String CURRENT_LINE_ATTRIBUTE = "protocol.http.current_line";
	private static final String HEADERS_RECEIVER_ATTRIBUTE = "protocol.http.headers_receiver";
	private static final String RECEIVE_STATUS_ATTRIBUTE = "protocol.http.receive_status";
	private static final String BODY_TRANSFER_ATTRIBUTE = "protocol.http.receive.body.io";
	private static final String LAST_RESPONSE_SENT_ATTRIBUTE = "protocol.http.send.last";
	
	public static final String REQUEST_START_RECEIVE_NANOTIME_ATTRIBUTE = "protocol.http.request.receive.start.nanotime";
	public static final String REQUEST_END_RECEIVE_NANOTIME_ATTRIBUTE = "protocol.http.request.receive.end.nanotime";
	public static final String REQUEST_END_PROCESS_NANOTIME_ATTRIBUTE = "protocol.http.request.process.end.nanotime";
	public static final String UPGRADED_PROTOCOL_ATTRIBUTE = "protocol.http.upgrade";
	
	private static enum ReceiveStatus {
		RECEIVING_START, RECEIVING_HEADER, RECEIVING_BODY
	}
	
	/** Constructor. */
	public HTTPServerProtocol(HTTPRequestProcessor processor) {
		this(processor, null);
	}
	
	/** Constructor. */
	public HTTPServerProtocol(HTTPRequestProcessor processor, Map<String,ServerProtocol> upgradableProtocols) {
		this.processor = processor;
		this.upgradableProtocols = upgradableProtocols;
		this.logger = LCCore.getApplication().getLoggerFactory().getLogger(HTTPServerProtocol.class);
	}
	
	private Logger logger;
	private HTTPRequestProcessor processor;
	private Map<String,ServerProtocol> upgradableProtocols;
	private int receiveDataTimeout = 0;
	private boolean enableRangeRequests = false;
	
	public HTTPRequestProcessor getProcessor() { return processor; }
	
	public int getReceiveDataTimeout() {
		return receiveDataTimeout;
	}
	
	public void setReceiveDataTimeout(int timeout) {
		receiveDataTimeout = timeout;
	}
	
	/** Enable support of web socket protocol. */
	public void enableWebSocket(WebSocketServerProtocol wsProtocol) {
		if (upgradableProtocols == null)
			upgradableProtocols = new HashMap<>();
		upgradableProtocols.put("websocket", wsProtocol);
	}
	
	/** Before to send any response, check if the request contains a range header and handle it. */
	public void enableRangeRequests() {
		enableRangeRequests = true;
	}
	
	@Override
	public void startProtocol(TCPServerClient client) {
		try {
			client.setAttribute(RECEIVE_STATUS_ATTRIBUTE, ReceiveStatus.RECEIVING_START);
			client.waitForData(receiveDataTimeout);
		} catch (ClosedChannelException e) {
			client.closed();
		}
	}
	
	@Override
	public int getInputBufferSize() {
		return 16384;
	}
	
	@Override
	public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data, Runnable onbufferavailable) {
		if (client.getAttribute(REQUEST_START_RECEIVE_NANOTIME_ATTRIBUTE) == null)
			client.setAttribute(REQUEST_START_RECEIVE_NANOTIME_ATTRIBUTE, Long.valueOf(System.nanoTime()));
		ServerProtocol proto = (ServerProtocol)client.getAttribute(UPGRADED_PROTOCOL_ATTRIBUTE);
		if (proto != null) {
			proto.dataReceivedFromClient(client, data, onbufferavailable);
			return;
		}
		ReceiveStatus status = (ReceiveStatus)client.getAttribute(RECEIVE_STATUS_ATTRIBUTE);
		if (status.equals(ReceiveStatus.RECEIVING_BODY))
			receiveBody(client, data, onbufferavailable);
		else
			receiveHeader(client, data, onbufferavailable);
	}
	
	@SuppressWarnings("resource")
	private void receiveHeader(TCPServerClient client, ByteBuffer data, Runnable onbufferavailable) {
		client.setAttribute(RECEIVE_STATUS_ATTRIBUTE, ReceiveStatus.RECEIVING_HEADER);
		HTTPRequest request = (HTTPRequest)client.getAttribute(REQUEST_ATTRIBUTE);
		if (request == null) {
			request = new HTTPRequest();
			client.setAttribute(REQUEST_ATTRIBUTE, request);
		}
		StringBuilder line = (StringBuilder)client.getAttribute(CURRENT_LINE_ATTRIBUTE);
		if (line == null) {
			line = new StringBuilder(128);
			client.setAttribute(CURRENT_LINE_ATTRIBUTE, line);
		}
		MimeUtil.HeadersLinesReceiver linesReceiver = (MimeUtil.HeadersLinesReceiver)client.getAttribute(HEADERS_RECEIVER_ATTRIBUTE);
		if (linesReceiver == null) {
			linesReceiver = new MimeUtil.HeadersLinesReceiver(request.getMIME().getHeaders());
			client.setAttribute(HEADERS_RECEIVER_ATTRIBUTE, linesReceiver);
		}
		while (data.hasRemaining()) {
			char c = (char)(data.get() & 0xFF);
			if (c == '\n') {
				String s;
				if (line.length() > 0 && line.charAt(line.length() - 1) == '\r')
					s = line.substring(0, line.length() - 1);
				else
					s = line.toString();
				if (s.isEmpty()) {
					// end of header
					client.removeAttribute(HEADERS_RECEIVER_ATTRIBUTE);
					try { linesReceiver.newLine(s); }
					catch (Exception e) {
						logger.error("Error parsing HTTP headers", e);
						HTTPServerResponse response = new HTTPServerResponse();
						response.forceClose = true;
						sendError(client, HttpURLConnection.HTTP_BAD_REQUEST,
							"Error parsing HTTP headers: " + e.getMessage(), request, response);
						onbufferavailable.run();
						return;
					}
					if (logger.trace()) {
						logger.trace("End of headers received");
					}
					if (logger.debug()) {
						logger.debug("HTTP Request: " + request.generateCommandLine());
					}
					// Analyze headers to check if an upgrade of the protocol is requested
					if (upgradableProtocols != null &&
						request.getMIME().hasHeader("Upgrade")) {
						String conn = request.getMIME().getFirstHeaderRawValue(MimeMessage.CONNECTION);
						boolean isUpgrade = false;
						if (conn != null)
							for (String str : conn.split(","))
								if (str.equalsIgnoreCase("Upgrade")) {
									isUpgrade = true;
									break;
								}
						if (isUpgrade) {
							// there is an upgrade request
							String protoName = request.getMIME().getFirstHeaderRawValue("Upgrade").trim().toLowerCase();
							ServerProtocol proto = upgradableProtocols.get(protoName);
							if (proto != null) {
								// the protocol is supported
								client.setAttribute(REQUEST_END_RECEIVE_NANOTIME_ATTRIBUTE,
									Long.valueOf(System.nanoTime()));
								client.setAttribute(UPGRADED_PROTOCOL_ATTRIBUTE, proto);
								logger.debug("Upgrading protocol to " + protoName);
								proto.startProtocol(client);
								if (data.hasRemaining())
									proto.dataReceivedFromClient(client, data, onbufferavailable);
								else
									onbufferavailable.run();
								return;
							}
						}
					}
					if (!request.isExpectingBody()) {
						client.setAttribute(REQUEST_END_RECEIVE_NANOTIME_ATTRIBUTE, Long.valueOf(System.nanoTime()));
						client.setAttribute(RECEIVE_STATUS_ATTRIBUTE, ReceiveStatus.RECEIVING_START);
						if (!data.hasRemaining())
							onbufferavailable.run();
						client.removeAttribute(REQUEST_ATTRIBUTE);
						client.removeAttribute(CURRENT_LINE_ATTRIBUTE);
						if (logger.trace())
							logger.trace("Start processing the request");
						// we are already in a CPU Thread, we can stay here
						HTTPServerResponse response = new HTTPServerResponse();
						@SuppressWarnings("unchecked")
						SynchronizationPoint<Exception> previousResponseSent =
							(SynchronizationPoint<Exception>)client.getAttribute(LAST_RESPONSE_SENT_ATTRIBUTE);
						client.setAttribute(LAST_RESPONSE_SENT_ATTRIBUTE, response.sent);
						processRequest(client, request, response, previousResponseSent);
						if (data.hasRemaining()) {
							dataReceivedFromClient(client, data, onbufferavailable);
							return;
						}
						if (request.isConnectionPersistent() && !client.hasAttribute(UPGRADED_PROTOCOL_ATTRIBUTE))
							try { client.waitForData(receiveDataTimeout); }
							catch (ClosedChannelException e) { client.closed(); }
						return;
					}
					if (logger.trace())
						logger.trace("Start receiving the body");
					// maximum 1MB in memory
					IOInMemoryOrFile io = new IOInMemoryOrFile(1024 * 1024, Task.PRIORITY_NORMAL, "HTTP Body");
					request.getMIME().setBodyReceived(io);
					client.addToClose(io);
					try {
						TransferReceiver transfer = TransferEncodingFactory.create(request.getMIME(), io);
						client.setAttribute(BODY_TRANSFER_ATTRIBUTE, transfer);
					} catch (IOException e) {
						logger.error("Error initializing body transfer", e);
						HTTPServerResponse response = new HTTPServerResponse();
						response.forceClose = true;
						sendError(client, HttpURLConnection.HTTP_BAD_REQUEST, e.getMessage(), request, response);
						onbufferavailable.run();
						client.close();
						return;
					}
					client.setAttribute(RECEIVE_STATUS_ATTRIBUTE, ReceiveStatus.RECEIVING_BODY);
					receiveBody(client, data, onbufferavailable);
					return;
				}
				if (logger.trace())
					logger.trace("Request header line received: " + line.toString().trim());
				if (request.isCommandSet())
					try { linesReceiver.newLine(s); }
					catch (Exception e) {
						logger.error("Error parsing HTTP headers", e);
						HTTPServerResponse response = new HTTPServerResponse();
						response.forceClose = true;
						sendError(client, HttpURLConnection.HTTP_BAD_REQUEST,
							"Error parsing HTTP headers: " + e.getMessage(), request, response);
						line.setLength(0);
						onbufferavailable.run();
						return;
					}
				else {
					try { request.setCommand(s); }
					catch (Exception e) {
						logger.error("Invalid HTTP command: " + s, e);
						HTTPServerResponse response = new HTTPServerResponse();
						response.forceClose = true;
						sendError(client, HttpURLConnection.HTTP_BAD_REQUEST, e.getMessage(), request, response);
						line.setLength(0);
						onbufferavailable.run();
						return;
					}
				}
				line.setLength(0);
				continue;
			}
			line.append(c);
		}
		onbufferavailable.run();
		try { client.waitForData(receiveDataTimeout); }
		catch (ClosedChannelException e) { client.closed(); }
	}
	
	private void receiveBody(TCPServerClient client, ByteBuffer data, Runnable onbufferavailable) {
		HTTPRequest request = (HTTPRequest)client.getAttribute(REQUEST_ATTRIBUTE);
		TransferReceiver transfer = (TransferReceiver)client.getAttribute(BODY_TRANSFER_ATTRIBUTE);
		AsyncWork<Boolean, IOException> sp = transfer.consume(data);
		sp.listenInline(new AsyncWorkListener<Boolean, IOException>() {
			@Override
			public void ready(Boolean result) {
				if (result.booleanValue()) {
					// end of body reached
					@SuppressWarnings("resource")
					IO.Readable.Seekable io = (IO.Readable.Seekable)request.getMIME().getBodyReceivedAsInput();
					try { io.seekSync(SeekType.FROM_BEGINNING, 0); }
					catch (Throwable e) { /* ignore */ }
					client.removeAttribute(REQUEST_ATTRIBUTE);
					client.removeAttribute(BODY_TRANSFER_ATTRIBUTE);
					client.removeAttribute(CURRENT_LINE_ATTRIBUTE);
					client.setAttribute(REQUEST_END_RECEIVE_NANOTIME_ATTRIBUTE, Long.valueOf(System.nanoTime()));
					client.setAttribute(RECEIVE_STATUS_ATTRIBUTE, ReceiveStatus.RECEIVING_START);
					// process it in a new task as we are in an inline listener
					HTTPServerResponse response = new HTTPServerResponse();
					@SuppressWarnings("unchecked")
					SynchronizationPoint<Exception> previousResponseSent =
						(SynchronizationPoint<Exception>)client.getAttribute(LAST_RESPONSE_SENT_ATTRIBUTE);
					client.setAttribute(LAST_RESPONSE_SENT_ATTRIBUTE, response.sent);
					client.addPending(new Task.Cpu<Void,NoException>("Processing HTTP request", Task.PRIORITY_NORMAL) {
						@Override
						public Void run() {
							processRequest(client, request, response, previousResponseSent);
							return null;
						}
					}.start().getOutput());
				}
				if (!data.hasRemaining())
					onbufferavailable.run();
				else {
					dataReceivedFromClient(client, data, onbufferavailable);
					return;
				}
				if (!result.booleanValue() || request.isConnectionPersistent())
					try { client.waitForData(receiveDataTimeout); }
					catch (ClosedChannelException e) { client.closed(); }
			}
			
			@Override
			public void error(IOException error) {
				onbufferavailable.run();
				logger.error("Error receiving body from client", error);
				client.close();
			}
			
			@Override
			public void cancelled(CancelException event) {
			}
		});
	}
	
	@Override
	public LinkedList<ByteBuffer> prepareDataToSend(TCPServerClient client, ByteBuffer data) {
		LinkedList<ByteBuffer> list = new LinkedList<>();
		list.add(data);
		return list;
	}
	
	private void processRequest(
		TCPServerClient client, HTTPRequest request, HTTPServerResponse response,
		SynchronizationPoint<Exception> previousResponseSent
	) {
		ISynchronizationPoint<?> processing = processor.process(client, request, response);
		client.addPending(processing);
		processing.listenAsync(new Task.Cpu<Void, NoException>("Start sending HTTP response", Task.PRIORITY_NORMAL) {
			@SuppressWarnings("resource")
			@Override
			public Void run() {
				if (processing.isCancelled()) {
					client.close();
					IO.Readable responseBody = response.getMIME().getBodyToSend();
					if (responseBody != null) responseBody.closeAsync();
					response.sent.cancel(processing.getCancelEvent());
					return null;
				}
				if (processing.hasError()) {
					Exception error = processing.getError();
					if (error instanceof HTTPResponseError) {
						response.setStatus(((HTTPResponseError)error).getStatusCode());
					} else {
						response.setStatus(500);
					}
				}
				if (response.getStatusCode() < 100)
					response.setStatus(500);
				
				if (enableRangeRequests)
					handleRangeRequest(request, response);

				sendResponse(client, request, response, previousResponseSent);
				return null;
			}
		}, true);
	}
	
	/** Send an error response to the client. */
	public static void sendError(
		TCPServerClient client, int status, String message, HTTPRequest request, HTTPServerResponse response
	) {
		@SuppressWarnings("unchecked")
		SynchronizationPoint<Exception> previousResponseSent =
			(SynchronizationPoint<Exception>)client.getAttribute(LAST_RESPONSE_SENT_ATTRIBUTE);
		client.setAttribute(LAST_RESPONSE_SENT_ATTRIBUTE, response.sent);
		response.setStatus(status, message);
		sendResponse(client, request, response, previousResponseSent);
	}
	
	/** Send a response to the client. */
	public static void sendResponse(TCPServerClient client, HTTPRequest request, HTTPServerResponse response) {
		@SuppressWarnings("unchecked")
		SynchronizationPoint<Exception> previousResponseSent =
			(SynchronizationPoint<Exception>)client.getAttribute(LAST_RESPONSE_SENT_ATTRIBUTE);
		client.setAttribute(LAST_RESPONSE_SENT_ATTRIBUTE, response.sent);
		sendResponse(client, request, response, previousResponseSent);
	}
	
	@SuppressWarnings("resource")
	private static void sendResponse(
		TCPServerClient client, HTTPRequest request, HTTPServerResponse response,
		SynchronizationPoint<Exception> previousResponseSent
	) {
		if (previousResponseSent == null) {
			sendResponseReady(client, request, response);
			return;
		}
		if (previousResponseSent.isCancelled()) {
			client.close();
			IO.Readable responseBody = response.getMIME().getBodyToSend();
			if (responseBody != null) responseBody.closeAsync();
			response.sent.cancel(previousResponseSent.getCancelEvent());
			return;
		}
		if (previousResponseSent.hasError()) {
			client.close();
			IO.Readable responseBody = response.getMIME().getBodyToSend();
			if (responseBody != null) responseBody.closeAsync();
			response.sent.error(previousResponseSent.getError());
			return;
		}
		if (previousResponseSent.isUnblocked()) {
			sendResponseReady(client, request, response);
			return;
		}
		previousResponseSent.listenAsync(new Task.Cpu<Void, NoException>("Start sending HTTP response", Task.PRIORITY_NORMAL) {
			@Override
			public Void run() {
				sendResponseReady(client, request, response);
				return null;
			}
		}, true);
	}
	
	private static void sendResponseReady(
		TCPServerClient client, HTTPRequest request, HTTPServerResponse response
	) {
		@SuppressWarnings("resource")
		IO.Readable body = response.getMIME().getBodyToSend();
		if (body == null) {
			sendResponse(client, request, response, null, 0);
			return;
		}
		if (body instanceof IO.KnownSize) {
			((IO.KnownSize)body).getSizeAsync().listenInline(
				(size) -> { sendResponse(client, request, response, body, size.longValue()); },
				(error) -> { response.sent.error(error); },
				(cancel) -> { response.sent.cancel(cancel); }
			);
			return;
		}
		if (body instanceof IO.OutputToInput) {
			IO.OutputToInput out2in = (IO.OutputToInput)body;
			// delay the check when some data is ready, so short response won't be chunked
			ISynchronizationPoint<IOException> ready = body.canStartReading();
			if (ready.isUnblocked()) {
				if (out2in.isFullDataAvailable())
					sendResponse(client, request, response, body, out2in.getAvailableDataSize());
				else
					sendResponse(client, request, response, body, -1);

			} else
				ready.listenAsync(new Task.Cpu.FromRunnable("Start sending HTTP response", Task.PRIORITY_NORMAL, () -> {
					if (out2in.isFullDataAvailable())
						sendResponse(client, request, response, body, out2in.getAvailableDataSize());
					else
						sendResponse(client, request, response, body, -1);
				}), true);
			return;
		}
		sendResponse(client, request, response, body, -1);
	}

	private static void sendResponse(
		TCPServerClient client, HTTPRequest request, HTTPServerResponse response, IO.Readable body, long bodySize
	) {
		if (!response.getMIME().hasHeader(HTTPResponse.SERVER_HEADER))
			response.getMIME().setHeaderRaw(HTTPResponse.SERVER_HEADER,
				"net.lecousin.framework.network.http.server/" + LibraryVersion.VERSION);
		if (bodySize >= 0)
			response.getMIME().setContentLength(bodySize);
		else
			response.getMIME().setHeaderRaw(MimeMessage.TRANSFER_ENCODING, "chunked");
		
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(HTTPServerProtocol.class);
		
		endOfProcessing(client, logger);
		
		if (logger.debug())
			logger.debug("Response code " + response.getStatusCode() + " for request "
				+ request.generateCommandLine() + " to send " + (bodySize >= 0 ? "with Content-Length=" + bodySize : "chunked"));

		Protocol protocol = response.getProtocol();
		if (protocol == null) protocol = request.getProtocol();
		if (protocol == null) protocol = Protocol.HTTP_1_1;
		byte[] status = (protocol.getName() + ' ' + Integer.toString(response.getStatusCode()) + ' ' + response.getStatusMessage() + "\r\n")
			.getBytes(StandardCharsets.US_ASCII);
		try {
			client.send(ByteBuffer.wrap(status), false);
		} catch (Exception e) {
			if (body != null) body.closeAsync();
			if (request.getMIME().getBodyReceivedAsOutput() != null) request.getMIME().getBodyReceivedAsOutput().closeAsync();
			client.close();
			response.sent.error(e);
			return;
		}

		UnprotectedStringBuffer s = new UnprotectedStringBuffer(new UnprotectedString(2048));
		response.getMIME().appendHeadersTo(s);
		s.append("\r\n");
		byte[] headers = s.toUsAsciiBytes();
		if (logger.trace())
			logger.trace("Sending response with headers:\n" + s);
		SynchronizationPoint<IOException> sendHeaders;
		try {
			sendHeaders = client.send(
				ByteBuffer.wrap(headers), bodySize == 0 && (!request.isConnectionPersistent() || response.forceClose));
		} catch (Exception e) {
			if (logger.error())
				logger.error("Error sending HTTP headers", e);
			if (body != null) body.closeAsync();
			if (request.getMIME().getBodyReceivedAsOutput() != null) request.getMIME().getBodyReceivedAsOutput().closeAsync();
			client.close();
			response.sent.error(e);
			return;
		}
		if (bodySize == 0) {
			// empty answer
			if (body != null) body.closeAsync();
			if (request.getMIME().getBodyReceivedAsOutput() != null) request.getMIME().getBodyReceivedAsOutput().closeAsync();
			sendHeaders.listenInlineSP(response.sent);
			return;
		}
		if (bodySize < 0) {
			sendResponseChunked(client, request, body, response.sent);
			return;
		}
		if (body instanceof IO.Readable.Buffered) {
			sendResponseBuffered(client, request, response, (IO.Readable.Buffered)body, sendHeaders);
			return;
		}
		MutableLong size = new MutableLong(bodySize);
		int bufferSize = size.get() > 256 * 1024 ? 256 * 1024 : (int)size.get();
		Mutable<ByteBuffer> buf = new Mutable<>(ByteBuffer.allocate(bufferSize));
		Mutable<AsyncWork<Integer,IOException>> read = new Mutable<>(body.readFullyAsync(buf.get()));
		JoinPoint<IOException> jp = new JoinPoint<>();
		jp.addToJoin(sendHeaders);
		jp.addToJoin(read.get());
		jp.start();
		jp.listenInline(new Runnable() {
			@Override
			public void run() {
				if (jp.hasError() || jp.isCancelled()) {
					body.closeAsync();
					client.close();
					if (jp.hasError()) response.sent.error(jp.getError());
					else response.sent.cancel(jp.getCancelEvent());
					return;
				}
				buf.get().flip();
				size.set(size.get() - read.get().getResult().intValue());
				SynchronizationPoint<IOException> send;
				try {
					send = client.send(buf.get(),
						size.get() > 0 ? false : !request.isConnectionPersistent() || response.forceClose);
				} catch (IOException e) {
					body.closeAsync();
					client.close();
					response.sent.error(e);
					return;
				}
				if (size.get() > 0) {
					buf.set(ByteBuffer.allocate(bufferSize));
					read.set(body.readFullyAsync(buf.get()));
					JoinPoint<IOException> jp = new JoinPoint<>();
					jp.addToJoin(send);
					jp.addToJoin(read.get());
					jp.start();
					jp.listenInline(this);
				} else {
					response.sent.unblock();
					body.closeAsync();
					if (request.getMIME().getBodyReceivedAsOutput() != null)
						request.getMIME().getBodyReceivedAsOutput().closeAsync();
				}
			}
		});
	}
	
	private static void sendResponseBuffered(
		TCPServerClient client, HTTPRequest request, HTTPServerResponse response, IO.Readable.Buffered body,
		ISynchronizationPoint<? extends Exception> previousSend
	) {
		body.readNextBufferAsync().listenInline(
			(buffer) -> {
				previousSend.listenInline(() -> {
					if (previousSend.isCancelled()) {
						body.closeAsync();
						client.close();
						response.sent.cancel(previousSend.getCancelEvent());
						return;
					}
					if (previousSend.hasError()) {
						body.closeAsync();
						client.close();
						response.sent.error(previousSend.getError());
						return;
					}
					if (buffer == null) {
						body.closeAsync();
						if (!request.isConnectionPersistent() || response.forceClose)
							client.close();
						else if (request.getMIME().getBodyReceivedAsOutput() != null)
							request.getMIME().getBodyReceivedAsOutput().closeAsync();
						response.sent.unblock();
						return;
					}
					new Task.Cpu<Void, NoException>("Sending next HTTP response buffer", Task.PRIORITY_NORMAL) {
						@Override
						public Void run() {
							try {
								sendResponseBuffered(client, request, response, body, client.send(buffer, false));
							} catch (ClosedChannelException e) {
								body.closeAsync();
								client.close();
								response.sent.cancel(new CancelException("Client closed"));
							}
							return null;
						}
					}.start();
				});
			},
			(error) -> {
				body.closeAsync();
				client.close();
				response.sent.error(error);
				return;
			},
			(cancel) -> {
				body.closeAsync();
				client.close();
				response.sent.error(cancel);
				return;
			}
		);
	}
	
	@SuppressWarnings("resource")
	private static void sendResponseChunked(
		TCPServerClient client, HTTPRequest request, IO.Readable body, SynchronizationPoint<Exception> responseSent
	) {
		IO.Readable.Buffered input;
		if (body instanceof IO.Readable.Buffered)
			input = (IO.Readable.Buffered)body;
		else
			input = new SimpleBufferedReadable(body, 65536);
		SynchronizationPoint<IOException> send = ChunkedTransfer.send(client, input);
		send.listenInline(() -> {
			input.closeAsync();
			if (request.getMIME().getBodyReceivedAsOutput() != null)
				request.getMIME().getBodyReceivedAsOutput().closeAsync();
			if (send.isCancelled()) {
				client.close();
				responseSent.cancel(send.getCancelEvent());
			} else if (send.hasError()) {
				client.close();
				responseSent.error(send.getError());
			} else
				responseSent.unblock();
		});
	}
	
	private static void endOfProcessing(TCPServerClient client, Logger logger) {
		long now = System.nanoTime();
		long connTime = ((Long)client.getAttribute(ServerProtocol.ATTRIBUTE_CONNECTION_ESTABLISHED_NANOTIME)).longValue();
		long startReceive = ((Long)client.getAttribute(HTTPServerProtocol.REQUEST_START_RECEIVE_NANOTIME_ATTRIBUTE)).longValue();
		Long l = (Long)client.getAttribute(HTTPServerProtocol.REQUEST_END_RECEIVE_NANOTIME_ATTRIBUTE);
		long endReceive = l != null ? l.longValue() : now;
		client.setAttribute(HTTPServerProtocol.REQUEST_END_PROCESS_NANOTIME_ATTRIBUTE, Long.valueOf(now));
		if (logger.debug())
			logger.debug("HTTP request processed: start receive "
				+ String.format("%.5f", new Double((startReceive - connTime) * 1.d / 1000000000))
				+ "s. after connection, request received in "
				+ String.format("%.5f",new Double((endReceive - startReceive) * 1.d / 1000000000))
				+ "s. and processed in "
				+ String.format("%.5f", new Double((now - endReceive) * 1.d / 1000000000)) + "s.");
	}

	/**
	 * By default range requests are disabled. It may be enabled globally by calling the method
	 * {@link #enableRangeRequests()} or by calling this method only on the requests we want to enable it.
	 */
	@SuppressWarnings("resource")
	public static void handleRangeRequest(HTTPRequest request, HTTPResponse response) {
		IO.Readable io = response.getMIME().getBodyToSend();
		if (io == null) return;
		if (!(io instanceof IO.Readable.Seekable)) return;
		if (!(io instanceof IO.KnownSize)) return;
		if (response.getStatusCode() != 200) return;
		
		response.setHeaderRaw("Accept-Ranges", "bytes");
			
		MimeHeader rangeHeader = request.getMIME().getFirstHeader("Range");
		if (rangeHeader == null) return;
		String rangeStr = rangeHeader.getRawValue().trim();
		if (!rangeStr.startsWith("bytes=")) return;
		rangeStr = rangeStr.substring(6).trim();
		String[] rangesStr = rangeStr.split(",");
		if (rangesStr.length == 1) {
			long totalSize;
			try { totalSize = ((IO.KnownSize)io).getSizeSync(); }
			catch (Throwable t) { return; }
			RangeLong range = getRange(rangeStr, totalSize);
			if (range == null) return;
			if (range.max < range.min) {
				response.setStatus(416, "Invalid range");
				return;
			}
			SubIO.Readable.Seekable subIO;
			if (io instanceof IO.Readable.Buffered)
				subIO = new SubIO.Readable.Seekable.Buffered(
					(IO.Readable.Seekable & IO.Readable.Buffered)io,
					range.min, range.getLength(), io.getSourceDescription(), true);
			else
				subIO = new SubIO.Readable.Seekable(
					(IO.Readable.Seekable)io, range.min, range.getLength(), io.getSourceDescription(), true);
			response.getMIME().setBodyToSend(subIO);
			response.setStatus(206);
			response.getMIME().setHeaderRaw("Content-Range", range.min + "-" + range.max + "/" + totalSize);
			return;
		}
		// multipart
		MultipartEntity multipart = new MultipartEntity("byteranges");
		for (MimeHeader h : response.getMIME().getHeaders())
			if (!h.getNameLowerCase().startsWith("content-"))
				multipart.addHeader(h);
		long totalSize;
		try { totalSize = ((IO.KnownSize)io).getSizeSync(); }
		catch (Throwable t) { return; }
		for (String s : rangesStr) {
			RangeLong range = getRange(s, totalSize);
			if (range == null) return;
			if (range.max < range.min) {
				response.setStatus(416, "Invalid range");
				return;
			}
			SubIO.Readable.Seekable subIO;
			if (io instanceof IO.Readable.Buffered)
				subIO = new SubIO.Readable.Seekable.Buffered(
					(IO.Readable.Seekable & IO.Readable.Buffered)io,
					range.min, range.getLength(), io.getSourceDescription(), true);
			else
				subIO = new SubIO.Readable.Seekable(
					(IO.Readable.Seekable)io, range.min, range.getLength(), io.getSourceDescription(), true);
			MimeMessage part = new MimeMessage();
			part.setBodyToSend(subIO);
			for (MimeHeader h : response.getMIME().getHeaders())
				if (h.getNameLowerCase().startsWith("content-"))
					part.addHeader(h);
			part.setHeaderRaw("Content-Range", range.min + "-" + range.max + "/" + totalSize);
			multipart.add(part);
		}
		response.setStatus(206);
		response.setMIME(multipart);
	}
	
	private static RangeLong getRange(String rangeStr, long totalSize) {
		int i = rangeStr.indexOf('-');
		if (i < 0) return null;
		String minStr = rangeStr.substring(0, i);
		String maxStr = rangeStr.substring(i + 1);
		if (minStr.length() == 0) {
			long lastBytes;
			try { lastBytes = Long.parseLong(maxStr); }
			catch (Throwable t) { return null; }
			return new RangeLong(totalSize - lastBytes, totalSize - 1);
		}
		if (maxStr.length() == 0) {
			long start;
			try { start = Long.parseLong(minStr); }
			catch (Throwable t) { return null; }
			return new RangeLong(start, totalSize - 1);
		}

		long start;
		long end;
		try {
			start = Long.parseLong(minStr);
			end = Long.parseLong(maxStr);
		} catch (Throwable t) {
			return null;
		}
		return new RangeLong(start, end);
	}
}
