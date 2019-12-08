package net.lecousin.framework.network.http.server;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.AsyncSupplier.Listener;
import net.lecousin.framework.concurrent.async.CancelException;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.async.JoinPoint;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.SubIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.buffering.SimpleBufferedReadable;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.math.RangeLong;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.HTTPMessage.Protocol;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
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
import net.lecousin.framework.network.server.protocol.ServerProtocolCommonAttributes;
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
	
	private enum ReceiveStatus {
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
			if (c != '\n') {
				line.append(c);
				continue;
			}
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
					errorReadingHeader("Error parsing HTTP headers", e, client, request, onbufferavailable);
					return;
				}
				endOfHeader(client, request, data, onbufferavailable);
				return;
			}
			
			if (logger.trace())
				logger.trace("Request header line received: " + line.toString().trim());
			
			if (request.isCommandSet()) {
				try { linesReceiver.newLine(s); }
				catch (Exception e) {
					errorReadingHeader("Error parsing HTTP headers", e, client, request, onbufferavailable);
					line.setLength(0);
					return;
				}
			} else {
				try { request.setCommand(s); }
				catch (Exception e) {
					errorReadingHeader("Invalid HTTP command: " + s, e, client, request, onbufferavailable);
					line.setLength(0);
					return;
				}
			}
			line.setLength(0);
		}
		onbufferavailable.run();
		try { client.waitForData(receiveDataTimeout); }
		catch (ClosedChannelException e) { client.closed(); }
	}
	
	private void errorReadingHeader(String message, Exception e, TCPServerClient client, HTTPRequest request, Runnable onbufferavailable) {
		logger.error(message, e);
		HTTPServerResponse response = new HTTPServerResponse();
		response.setForceClose(true);
		sendError(client, HttpURLConnection.HTTP_BAD_REQUEST, message + ": " + e.getMessage(), request, response);
		onbufferavailable.run();
	}
	
	private void endOfHeader(TCPServerClient client, HTTPRequest request, ByteBuffer data, Runnable onbufferavailable) {
		if (logger.trace()) {
			logger.trace("End of headers received");
		}
		if (logger.debug()) {
			logger.debug("HTTP Request: " + request.generateCommandLine());
		}
		
		// Analyze headers to check if an upgrade of the protocol is requested
		if (handleUpgradeRequest(client, request, data, onbufferavailable))
			return;
		
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
			Async<IOException> previousResponseSent = (Async<IOException>)client.getAttribute(LAST_RESPONSE_SENT_ATTRIBUTE);
			client.setAttribute(LAST_RESPONSE_SENT_ATTRIBUTE, response.getSent());
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
			response.setForceClose(true);
			sendError(client, HttpURLConnection.HTTP_BAD_REQUEST, e.getMessage(), request, response);
			onbufferavailable.run();
			client.close();
			return;
		}
		client.setAttribute(RECEIVE_STATUS_ATTRIBUTE, ReceiveStatus.RECEIVING_BODY);
		receiveBody(client, data, onbufferavailable);
	}
	
	private boolean handleUpgradeRequest(TCPServerClient client, HTTPRequest request, ByteBuffer data, Runnable onbufferavailable) {
		if (upgradableProtocols == null || !request.getMIME().hasHeader(HTTPConstants.Headers.Request.UPGRADE))
			return false;
		String conn = request.getMIME().getFirstHeaderRawValue(HTTPConstants.Headers.Request.CONNECTION);
		boolean isUpgrade = false;
		if (conn != null)
			for (String str : conn.split(","))
				if (str.equalsIgnoreCase(HTTPConstants.Headers.Request.CONNECTION_VALUE_UPGRADE)) {
					isUpgrade = true;
					break;
				}
		if (!isUpgrade)
			return false;

		// there is an upgrade request
		String protoName = request.getMIME().getFirstHeaderRawValue(HTTPConstants.Headers.Request.UPGRADE).trim().toLowerCase();
		ServerProtocol proto = upgradableProtocols.get(protoName);
		if (proto == null)
			return false;

		// the protocol is supported
		client.setAttribute(REQUEST_END_RECEIVE_NANOTIME_ATTRIBUTE, Long.valueOf(System.nanoTime()));
		client.setAttribute(UPGRADED_PROTOCOL_ATTRIBUTE, proto);
		logger.debug("Upgrading protocol to " + protoName);
		proto.startProtocol(client);
		if (data.hasRemaining())
			proto.dataReceivedFromClient(client, data, onbufferavailable);
		else
			onbufferavailable.run();
		return true;
	}
	
	private void receiveBody(TCPServerClient client, ByteBuffer data, Runnable onbufferavailable) {
		HTTPRequest request = (HTTPRequest)client.getAttribute(REQUEST_ATTRIBUTE);
		TransferReceiver transfer = (TransferReceiver)client.getAttribute(BODY_TRANSFER_ATTRIBUTE);
		AsyncSupplier<Boolean, IOException> sp = transfer.consume(data);
		sp.listen(new Listener<Boolean, IOException>() {
			@Override
			public void ready(Boolean result) {
				if (result.booleanValue()) {
					// end of body reached
					IO.Readable.Seekable io = (IO.Readable.Seekable)request.getMIME().getBodyReceivedAsInput();
					try { io.seekSync(SeekType.FROM_BEGINNING, 0); }
					catch (Exception e) { /* ignore */ }
					client.removeAttribute(REQUEST_ATTRIBUTE);
					client.removeAttribute(BODY_TRANSFER_ATTRIBUTE);
					client.removeAttribute(CURRENT_LINE_ATTRIBUTE);
					client.setAttribute(REQUEST_END_RECEIVE_NANOTIME_ATTRIBUTE, Long.valueOf(System.nanoTime()));
					client.setAttribute(RECEIVE_STATUS_ATTRIBUTE, ReceiveStatus.RECEIVING_START);
					// process it in a new task as we are in an inline listener
					HTTPServerResponse response = new HTTPServerResponse();
					@SuppressWarnings("unchecked")
					Async<IOException> previousResponseSent =
						(Async<IOException>)client.getAttribute(LAST_RESPONSE_SENT_ATTRIBUTE);
					client.setAttribute(LAST_RESPONSE_SENT_ATTRIBUTE, response.getSent());
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
				// nothing
			}
		});
	}
	
	@Override
	public LinkedList<ByteBuffer> prepareDataToSend(TCPServerClient client, ByteBuffer data) {
		LinkedList<ByteBuffer> list = new LinkedList<>();
		list.add(data);
		return list;
	}
	
	private static class StartSendingResponse extends Task.Cpu.FromRunnable {
		
		private StartSendingResponse(Runnable runnable) {
			super("Start sending HTTP response", Task.PRIORITY_NORMAL, runnable);
		}
		
	}
	
	private void processRequest(
		TCPServerClient client, HTTPRequest request, HTTPServerResponse response,
		Async<IOException> previousResponseSent
	) {
		IAsync<?> processing = processor.process(client, request, response);
		client.addPending(processing);
		processing.thenStart(new StartSendingResponse(() -> {
			if (processing.isCancelled()) {
				client.close();
				IO.Readable responseBody = response.getMIME().getBodyToSend();
				if (responseBody != null) responseBody.closeAsync();
				response.getSent().cancel(processing.getCancelEvent());
				return;
			}
			if (processing.hasError()) {
				Exception error = processing.getError();
				if (error instanceof HTTPResponseError) {
					response.setStatus(((HTTPResponseError)error).getStatusCode());
				} else {
					response.setStatus(HttpURLConnection.HTTP_INTERNAL_ERROR);
				}
			}
			if (response.getStatusCode() < 100)
				response.setStatus(HttpURLConnection.HTTP_INTERNAL_ERROR);
			
			if (enableRangeRequests)
				handleRangeRequest(request, response);

			sendResponse(client, request, response, previousResponseSent);
		}), true);
	}
	
	/** Send an error response to the client. */
	public static void sendError(
		TCPServerClient client, int status, String message, HTTPRequest request, HTTPServerResponse response
	) {
		@SuppressWarnings("unchecked")
		Async<IOException> previousResponseSent =
			(Async<IOException>)client.getAttribute(LAST_RESPONSE_SENT_ATTRIBUTE);
		client.setAttribute(LAST_RESPONSE_SENT_ATTRIBUTE, response.getSent());
		response.setStatus(status, message);
		sendResponse(client, request, response, previousResponseSent);
	}
	
	/** Send a response to the client. */
	public static void sendResponse(TCPServerClient client, HTTPRequest request, HTTPServerResponse response) {
		@SuppressWarnings("unchecked")
		Async<IOException> previousResponseSent =
			(Async<IOException>)client.getAttribute(LAST_RESPONSE_SENT_ATTRIBUTE);
		client.setAttribute(LAST_RESPONSE_SENT_ATTRIBUTE, response.getSent());
		sendResponse(client, request, response, previousResponseSent);
	}
	
	private static void sendResponse(
		TCPServerClient client, HTTPRequest request, HTTPServerResponse response,
		Async<IOException> previousResponseSent
	) {
		if (previousResponseSent == null) {
			sendResponseReady(client, request, response);
			return;
		}
		if (previousResponseSent.isSuccessful()) {
			sendResponseReady(client, request, response);
			return;
		}
		if (previousResponseSent.hasError() || previousResponseSent.isCancelled()) {
			client.close();
			IO.Readable responseBody = response.getMIME().getBodyToSend();
			if (responseBody != null) responseBody.closeAsync();
			previousResponseSent.forwardIfNotSuccessful(response.getSent());
			return;
		}
		previousResponseSent.thenStart(new StartSendingResponse(() -> sendResponseReady(client, request, response)), true);
	}
	
	private static void sendResponseReady(
		TCPServerClient client, HTTPRequest request, HTTPServerResponse response
	) {
		IO.Readable body = response.getMIME().getBodyToSend();
		if (body == null) {
			sendResponse(client, request, response, null, 0);
			return;
		}
		if (body instanceof IO.KnownSize) {
			((IO.KnownSize)body).getSizeAsync().onDone(
				size -> sendResponse(client, request, response, body, size.longValue()), response.getSent());
			return;
		}
		if (body instanceof IO.OutputToInput) {
			IO.OutputToInput out2in = (IO.OutputToInput)body;
			// delay the check when some data is ready, so short response won't be chunked
			IAsync<IOException> ready = body.canStartReading();
			if (ready.isDone()) {
				if (out2in.isFullDataAvailable())
					sendResponse(client, request, response, body, out2in.getAvailableDataSize());
				else
					sendResponse(client, request, response, body, -1);

			} else {
				ready.thenStart(new StartSendingResponse(() -> {
					if (out2in.isFullDataAvailable())
						sendResponse(client, request, response, body, out2in.getAvailableDataSize());
					else
						sendResponse(client, request, response, body, -1);
				}), true);
			}
			return;
		}
		sendResponse(client, request, response, body, -1);
	}

	// skip checkstyle: OverloadMethodsDeclarationOrder
	private static void sendResponse(
		TCPServerClient client, HTTPRequest request, HTTPServerResponse response, IO.Readable body, long bodySize
	) {
		if (!response.getMIME().hasHeader(HTTPConstants.Headers.Response.SERVER))
			response.getMIME().setHeaderRaw(HTTPConstants.Headers.Response.SERVER, HTTPConstants.Headers.Response.DEFAULT_SERVER);
		
		Supplier<List<MimeHeader>> trailerSupplier = response.getTrailerHeadersSuppliers();
		
		if (!response.isForceNoContent()) {
			if (bodySize >= 0 && trailerSupplier == null)
				response.getMIME().setContentLength(bodySize);
			else
				response.getMIME().setHeaderRaw(MimeMessage.TRANSFER_ENCODING, ChunkedTransfer.TRANSFER_NAME);
		}
		
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(HTTPServerProtocol.class);
		
		endOfProcessing(client, logger);
		
		if (logger.debug())
			logger.debug("Response code " + response.getStatusCode() + " for request "
				+ request.generateCommandLine() + " to send " + (bodySize >= 0 ? "with Content-Length=" + bodySize : "chunked"));

		Protocol protocol = response.getProtocol();
		if (protocol == null) protocol = request.getProtocol();
		if (protocol == null) protocol = Protocol.HTTP_1_1;
		UnprotectedStringBuffer s = new UnprotectedStringBuffer(new UnprotectedString(512));
		s.append(protocol.getName()).append(' ').append(Integer.toString(response.getStatusCode()))
			.append(' ').append(response.getStatusMessage()).append("\r\n");
		response.getMIME().appendHeadersTo(s);
		s.append("\r\n");
		byte[] headers = s.toUsAsciiBytes();
		if (logger.trace())
			logger.trace("Sending response with headers:\n" + s);
		
		Async<IOException> sendHeaders;
		try {
			sendHeaders = client.send(
				ByteBuffer.wrap(headers), bodySize == 0 && (!request.isConnectionPersistent() || response.isForceClose()));
		} catch (Exception e) {
			if (logger.error())
				logger.error("Error sending HTTP headers", e);
			if (body != null) body.closeAsync();
			if (request.getMIME().getBodyReceivedAsOutput() != null) request.getMIME().getBodyReceivedAsOutput().closeAsync();
			client.close();
			response.getSent().error(IO.error(e));
			return;
		}
		
		if (bodySize == 0 && trailerSupplier == null) {
			// empty answer
			if (body != null) body.closeAsync();
			if (request.getMIME().getBodyReceivedAsOutput() != null) request.getMIME().getBodyReceivedAsOutput().closeAsync();
			sendHeaders.onDone(response.getSent());
			return;
		}
		if (bodySize < 0 || trailerSupplier != null) {
			sendResponseChunked(client, request, body, trailerSupplier, response.getSent());
			return;
		}
		if (body instanceof IO.Readable.Buffered) {
			sendResponseBuffered(client, request, response, (IO.Readable.Buffered)body, sendHeaders);
			return;
		}
		
		sendNextBuffer(client, request, response, sendHeaders, body, bodySize);
	}
	
	private static void sendResponseBuffered(
		TCPServerClient client, HTTPRequest request, HTTPServerResponse response, IO.Readable.Buffered body,
		IAsync<IOException> previousSend
	) {
		body.readNextBufferAsync().onDone(
			buffer -> previousSend.onDone(() -> {
				if (!previousSend.isSuccessful()) {
					body.closeAsync();
					client.close();
					previousSend.forwardIfNotSuccessful(response.getSent());
					return;
				}
				if (buffer == null) {
					body.closeAsync();
					if (!request.isConnectionPersistent() || response.isForceClose())
						client.close();
					else if (request.getMIME().getBodyReceivedAsOutput() != null)
						request.getMIME().getBodyReceivedAsOutput().closeAsync();
					response.getSent().unblock();
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
							response.getSent().cancel(new CancelException("Client closed"));
						}
						return null;
					}
				}.start();
			}),
			error -> {
				body.closeAsync();
				client.close();
				response.getSent().error(error);
				return;
			},
			cancel -> {
				body.closeAsync();
				client.close();
				response.getSent().error(IO.errorCancelled(cancel));
				return;
			}
		);
	}
	
	private static void sendResponseChunked(
		TCPServerClient client,
		HTTPRequest request, IO.Readable body,
		Supplier<List<MimeHeader>> trailersSupplier,
		Async<IOException> responseSent
	) {
		IO.Readable.Buffered input;
		if (body instanceof IO.Readable.Buffered)
			input = (IO.Readable.Buffered)body;
		else
			input = new SimpleBufferedReadable(body, 65536);
		Async<IOException> send = ChunkedTransfer.send(client, input, trailersSupplier);
		send.onDone(() -> {
			input.closeAsync();
			if (request.getMIME().getBodyReceivedAsOutput() != null)
				request.getMIME().getBodyReceivedAsOutput().closeAsync();
			if (send.isCancelled()) {
				client.close();
				responseSent.cancel(send.getCancelEvent());
			} else if (send.hasError()) {
				client.close();
				responseSent.error(send.getError());
			} else {
				responseSent.unblock();
			}
		});
	}

	private static void sendNextBuffer(
		TCPServerClient client, HTTPRequest request, HTTPServerResponse response,
		IAsync<IOException> previousSend, IO.Readable body, long remainingSize
	) {
		int bufferSize = remainingSize > 256 * 1024 ? 256 * 1024 : (int)remainingSize;
		ByteBuffer buf = ByteBuffer.allocate(bufferSize);
		AsyncSupplier<Integer,IOException> read = body.readFullyAsync(buf);
		JoinPoint<IOException> jp = new JoinPoint<>();
		jp.addToJoin(previousSend);
		jp.addToJoin(read);
		jp.start();
		jp.onDone(() -> {
			if (!jp.isSuccessful()) {
				body.closeAsync();
				client.close();
				jp.forwardIfNotSuccessful(response.getSent());
				return;
			}
			buf.flip();
			long size = remainingSize - read.getResult().intValue();
			Async<IOException> send;
			try {
				send = client.send(buf, size <= 0 && (!request.isConnectionPersistent() || response.isForceClose()));
			} catch (IOException e) {
				body.closeAsync();
				client.close();
				response.getSent().error(e);
				return;
			}
			if (size > 0) {
				sendNextBuffer(client, request, response, send, body, size);
			} else {
				response.getSent().unblock();
				body.closeAsync();
				if (request.getMIME().getBodyReceivedAsOutput() != null)
					request.getMIME().getBodyReceivedAsOutput().closeAsync();
			}
		});
	}
	
	private static void endOfProcessing(TCPServerClient client, Logger logger) {
		long now = System.nanoTime();
		long connTime = ((Long)client.getAttribute(ServerProtocolCommonAttributes.ATTRIBUTE_CONNECTION_ESTABLISHED_NANOTIME)).longValue();
		long startReceive = ((Long)client.getAttribute(HTTPServerProtocol.REQUEST_START_RECEIVE_NANOTIME_ATTRIBUTE)).longValue();
		Long l = (Long)client.getAttribute(HTTPServerProtocol.REQUEST_END_RECEIVE_NANOTIME_ATTRIBUTE);
		long endReceive = l != null ? l.longValue() : now;
		client.setAttribute(HTTPServerProtocol.REQUEST_END_PROCESS_NANOTIME_ATTRIBUTE, Long.valueOf(now));
		if (logger.debug())
			logger.debug("HTTP request processed: start receive "
				+ String.format("%.5f", Double.valueOf((startReceive - connTime) * 1.d / 1000000000))
				+ "s. after connection, request received in "
				+ String.format("%.5f", Double.valueOf((endReceive - startReceive) * 1.d / 1000000000))
				+ "s. and processed in "
				+ String.format("%.5f", Double.valueOf((now - endReceive) * 1.d / 1000000000)) + "s.");
	}

	/**
	 * By default range requests are disabled. It may be enabled globally by calling the method
	 * {@link #enableRangeRequests()} or by calling this method only on the requests we want to enable it.
	 */
	public static void handleRangeRequest(HTTPRequest request, HTTPResponse response) {
		IO.Readable io = response.getMIME().getBodyToSend();
		if (io == null) return;
		if (!(io instanceof IO.Readable.Seekable)) return;
		if (!(io instanceof IO.KnownSize)) return;
		if (response.getStatusCode() != 200) return;
		
		response.setHeaderRaw(HTTPConstants.Headers.Response.ACCEPT_RANGES, HTTPConstants.Headers.Response.ACCEPT_RANGES_VALUE_BYTES);
			
		MimeHeader rangeHeader = request.getMIME().getFirstHeader(HTTPConstants.Headers.Request.RANGE);
		if (rangeHeader == null) return;
		String rangeStr = rangeHeader.getRawValue().trim();
		if (!rangeStr.startsWith("bytes=")) return;
		rangeStr = rangeStr.substring(6).trim();
		String[] rangesStr = rangeStr.split(",");

		long totalSize;
		try { totalSize = ((IO.KnownSize)io).getSizeSync(); }
		catch (Exception t) { return; }
		
		if (rangesStr.length == 1) {
			RangeLong range = getRange(rangeStr, totalSize);
			SubIO.Readable.Seekable subIO = openRange(range, (IO.Readable.Seekable)io, response);
			if (subIO == null) return;
			response.getMIME().setBodyToSend(subIO);
			response.setStatus(206);
			response.getMIME().setHeaderRaw(HTTPConstants.Headers.Response.CONTENT_RANGE, range.min + "-" + range.max + "/" + totalSize);
			return;
		}
		
		// multipart
		MultipartEntity multipart = new MultipartEntity("byteranges");
		for (MimeHeader h : response.getMIME().getHeaders())
			if (!h.getNameLowerCase().startsWith("content-"))
				multipart.addHeader(h);
		for (String s : rangesStr) {
			RangeLong range = getRange(s, totalSize);
			SubIO.Readable.Seekable subIO = openRange(range, (IO.Readable.Seekable)io, response);
			if (subIO == null) return;
			MimeMessage part = new MimeMessage();
			part.setBodyToSend(subIO);
			for (MimeHeader h : response.getMIME().getHeaders())
				if (h.getNameLowerCase().startsWith("content-"))
					part.addHeader(h);
			part.setHeaderRaw(HTTPConstants.Headers.Response.CONTENT_RANGE, range.min + "-" + range.max + "/" + totalSize);
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
			catch (Exception t) { return null; }
			return new RangeLong(totalSize - lastBytes, totalSize - 1);
		}
		if (maxStr.length() == 0) {
			long start;
			try { start = Long.parseLong(minStr); }
			catch (Exception t) { return null; }
			return new RangeLong(start, totalSize - 1);
		}

		long start;
		long end;
		try {
			start = Long.parseLong(minStr);
			end = Long.parseLong(maxStr);
		} catch (Exception t) {
			return null;
		}
		return new RangeLong(start, end);
	}
	
	private static SubIO.Readable.Seekable openRange(RangeLong range, IO.Readable.Seekable io, HTTPResponse response) {
		if (range == null) return null;
		if (range.max < range.min) {
			response.setStatus(416, "Invalid range");
			return null;
		}
		if (io instanceof IO.Readable.Buffered)
			return new SubIO.Readable.Seekable.Buffered((IO.Readable.Seekable & IO.Readable.Buffered)io,
				range.min, range.getLength(), io.getSourceDescription(), true);
		return new SubIO.Readable.Seekable(io, range.min, range.getLength(), io.getSourceDescription(), true);
	}
}
