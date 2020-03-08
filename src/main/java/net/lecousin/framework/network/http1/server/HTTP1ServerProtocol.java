package net.lecousin.framework.network.http1.server;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.concurrent.util.AsyncTimeoutManager;
import net.lecousin.framework.concurrent.util.PartialAsyncConsumer;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.math.RangeLong;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.exception.HTTPError;
import net.lecousin.framework.network.http.exception.HTTPException;
import net.lecousin.framework.network.http.exception.UnsupportedHTTPProtocolException;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;
import net.lecousin.framework.network.http.server.HTTPServerResponse;
import net.lecousin.framework.network.http.server.errorhandler.DefaultErrorHandler;
import net.lecousin.framework.network.http.server.errorhandler.HTTPErrorHandler;
import net.lecousin.framework.network.http1.HTTP1RequestCommandConsumer;
import net.lecousin.framework.network.http1.HTTP1RequestCommandProducer;
import net.lecousin.framework.network.http1.HTTP1ResponseStatusProducer;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.entity.EmptyEntity;
import net.lecousin.framework.network.mime.entity.MimeEntity;
import net.lecousin.framework.network.mime.entity.MultipartEntity;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.transfer.ChunkedTransfer;
import net.lecousin.framework.network.mime.transfer.TransferEncodingFactory;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.ServerProtocol;
import net.lecousin.framework.text.ByteArrayStringIso8859Buffer;
import net.lecousin.framework.text.CharArrayString;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.Triple;

/** Implements the HTTP protocol on server side. */
public class HTTP1ServerProtocol implements ServerProtocol {

	public static final String REQUEST_ATTRIBUTE = "protocol.http.request";
	private static final String LIMITER_ATTRIBUTE = "protocol.http.request.limiter";
	private static final String RECEIVE_STATUS_ATTRIBUTE = "protocol.http.receive_status";
	private static final String RECEIVE_CONSUMER_ATTRIBUTE = "protocol.http.receive_consumer";
	
	public static final String REQUEST_START_RECEIVE_NANOTIME_ATTRIBUTE = "protocol.http.request.receive.start.nanotime";
	public static final String REQUEST_END_RECEIVE_NANOTIME_ATTRIBUTE = "protocol.http.request.receive.end.nanotime";
	public static final String REQUEST_END_PROCESS_NANOTIME_ATTRIBUTE = "protocol.http.request.process.end.nanotime";
	public static final String UPGRADED_PROTOCOL_ATTRIBUTE = "protocol.http.upgrade";
	public static final String UPGRADED_PROTOCOL_REQUEST_CONTEXT_ATTRIBUTE = "protocol.http.upgrade.response";
	
	/** Constructor. */
	public HTTP1ServerProtocol(HTTPRequestProcessor processor) {
		this(processor, null);
	}
	
	/** Constructor. */
	public HTTP1ServerProtocol(HTTPRequestProcessor processor, Map<String, HTTP1ServerUpgradeProtocol> upgradableProtocols) {
		this.processor = processor;
		this.upgradableProtocols = upgradableProtocols;
		this.logger = LCCore.getApplication().getLoggerFactory().getLogger(HTTP1ServerProtocol.class);
		this.bufferCache = ByteArrayCache.getInstance();
	}
	
	private Logger logger;
	private ByteArrayCache bufferCache;
	private HTTPRequestProcessor processor;
	private Map<String, HTTP1ServerUpgradeProtocol> upgradableProtocols;
	private int receiveDataTimeout = 0;
	private int sendDataTimeout = 0;
	private boolean enableRangeRequests = false;
	private int maximumHeaderLength = 65536;
	private int maximumPendingRequestsByClient = 8;
	private int maximumRequestProcessingTime = 60000;
	private HTTPErrorHandler errorHandler = DefaultErrorHandler.getInstance();
	private List<String> alternativeServices = new LinkedList<>(); // TODO max-age + persist
	
	public HTTPRequestProcessor getProcessor() { return processor; }
	
	public int getReceiveDataTimeout() {
		return receiveDataTimeout;
	}
	
	public void setReceiveDataTimeout(int timeout) {
		receiveDataTimeout = timeout;
	}
	
	public int getSendDataTimeout() {
		return sendDataTimeout;
	}
	
	public void setSendDataTimeout(int timeout) {
		sendDataTimeout = timeout;
	}
	
	public int getMaximumHeaderLength() {
		return maximumHeaderLength;
	}

	public void setMaximumHeaderLength(int maximumHeaderLength) {
		this.maximumHeaderLength = maximumHeaderLength;
	}
	
	public int getMaximumPendingRequestsByClient() {
		return maximumPendingRequestsByClient;
	}
	
	public void setMaximumPendingRequestsByClient(int maximumPendingRequestsByClient) {
		this.maximumPendingRequestsByClient = maximumPendingRequestsByClient;
	}
	
	public int getMaximumRequestProcessingTime() {
		return maximumRequestProcessingTime;
	}
	
	public void setMaximumRequestProcessingTime(int maximumRequestProcessingTime) {
		this.maximumRequestProcessingTime = maximumRequestProcessingTime;
	}
	
	public HTTPErrorHandler getErrorHandler() {
		return errorHandler;
	}

	public void setErrorHandler(HTTPErrorHandler errorHandler) {
		this.errorHandler = errorHandler;
	}

	/** Add a protocol that can be enabled using the Upgrade mechanisme (Section 6.7 of RFC7230). */
	public void addUpgradeProtocol(HTTP1ServerUpgradeProtocol upgradeProtocol) {
		if (upgradableProtocols == null)
			upgradableProtocols = new HashMap<>();
		upgradableProtocols.put(upgradeProtocol.getUpgradeProtocolToken(), upgradeProtocol);
	}
	
	/** Before to send any response, check if the request contains a range header and handle it. */
	public void enableRangeRequests() {
		enableRangeRequests = true;
	}
	
	/** Add an alternative service to be sent to clients using Alt-Svc header on first response. */
	public void addAlternativeService(String protocolId, String hostname, int port) {
		alternativeServices.add(protocolId + "=" + "\"" + (hostname != null ? hostname : "") + ":" + port + "\"");
	}
	
	@Override
	public int startProtocol(TCPServerClient client) {
		client.setAttribute(LIMITER_ATTRIBUTE, new RequestProcessingLimiter());
		client.setAttribute(RECEIVE_STATUS_ATTRIBUTE, ReceiveStatus.START);
		return receiveDataTimeout;
	}
	
	@Override
	public int getInputBufferSize() {
		return 16384;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data) {
		if (client.getAttribute(REQUEST_START_RECEIVE_NANOTIME_ATTRIBUTE) == null)
			client.setAttribute(REQUEST_START_RECEIVE_NANOTIME_ATTRIBUTE, Long.valueOf(System.nanoTime()));
		
		if (logger.trace())
			logger.trace("Received from client: " + data.remaining());
		
		// if upgrade protocol, forward data to the protocol;
		ServerProtocol proto = (ServerProtocol)client.getAttribute(UPGRADED_PROTOCOL_ATTRIBUTE);
		if (proto != null) {
			proto.dataReceivedFromClient(client, data);
			return;
		}
		
		ReceiveStatus status = (ReceiveStatus)client.getAttribute(RECEIVE_STATUS_ATTRIBUTE);
		PartialAsyncConsumer<ByteBuffer, Exception> consumer;
		if (ReceiveStatus.START.equals(status)) {
			HTTPRequest request = new HTTPRequest();
			client.setAttribute(REQUEST_ATTRIBUTE, request);
			if (!data.hasArray())
				data = ByteArray.Writable.fromByteBuffer(data).toByteBuffer();
			consumer = new HTTP1RequestCommandConsumer(request).convert(
				ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), e -> e);
			client.setAttribute(RECEIVE_CONSUMER_ATTRIBUTE, consumer);
			client.setAttribute(RECEIVE_STATUS_ATTRIBUTE, ReceiveStatus.COMMAND_LINE);
			if (logger.trace())
				logger.trace("Start receiving HTTP request command from client");
		} else {
			consumer = (PartialAsyncConsumer<ByteBuffer, Exception>)client.getAttribute(RECEIVE_CONSUMER_ATTRIBUTE);
		}
		AsyncSupplier<Boolean, ?> consumption = consumer.consume(data);
		if (consumption.isDone()) {
			endOfConsumption(client, data, consumption);
		} else {
			ByteBuffer d = data;
			consumption.thenStart("Receiving data from HTTP client", Task.Priority.NORMAL,
				() -> endOfConsumption(client, d, consumption), true);
		}
	}
	
	private enum ReceiveStatus {
		START, COMMAND_LINE, HEADERS, BODY
	}
	
	private void endOfConsumption(TCPServerClient client, ByteBuffer data, AsyncSupplier<Boolean, ?> consumption) {
		HTTPRequest request = (HTTPRequest)client.getAttribute(REQUEST_ATTRIBUTE);
		if (consumption.hasError()) {
			receiveError(client, consumption.getError(), request);
			return;
		}
		if (!consumption.getResult().booleanValue()) {
			// need more data
			bufferCache.free(data);
			((RequestProcessingLimiter)client.getAttribute(LIMITER_ATTRIBUTE)).needMoreData(client);
			return;
		}
		ReceiveStatus status = (ReceiveStatus)client.getAttribute(RECEIVE_STATUS_ATTRIBUTE);
		boolean needMoreData = false;
		switch (status) {
		case COMMAND_LINE:
			endOfCommandLine(client, request);
			needMoreData = true;
			break;
		case HEADERS:
			if (!endOfHeaders(client, request, data))
				return;
			needMoreData = request.isExpectingBody() ||
				(request.isConnectionPersistent() && !client.hasAttribute(UPGRADED_PROTOCOL_ATTRIBUTE));
			break;
		case BODY:
			endOfBody(client);
			needMoreData = request.isConnectionPersistent() && !client.hasAttribute(UPGRADED_PROTOCOL_ATTRIBUTE);
			break;
		default:
			// not possible
			break;
		}
		if (data.hasRemaining()) {
			dataReceivedFromClient(client, data);
			return;
		}
		bufferCache.free(data);
		if (needMoreData)
			((RequestProcessingLimiter)client.getAttribute(LIMITER_ATTRIBUTE)).needMoreData(client);
	}
	
	private void receiveError(TCPServerClient client, Exception error, HTTPRequest request) {
		logger.error("Error receiving data from client", error);
		HTTPRequestContext ctx = ((RequestProcessingLimiter)client.getAttribute(LIMITER_ATTRIBUTE)).newResponse(client, request);
		ctx.getResponse().setForceClose(true);
		int status;
		String message;
		if (error instanceof HTTPError) {
			status = ((HTTPError)error).getStatusCode();
			message = ((HTTPError)error).getMessage();
		} else {
			status = HttpURLConnection.HTTP_BAD_REQUEST;
			message = "Invalid request: " + error.getMessage();
		}
		errorHandler.setError(ctx, status, message, error);
	}
	
	private void endOfCommandLine(TCPServerClient client, HTTPRequest request) {
		if (logger.debug()) {
			logger.debug("HTTP Request: " + HTTP1RequestCommandProducer.generateString(request));
		}
		MimeHeaders headers = new MimeHeaders();
		MimeHeaders.HeadersConsumer headersConsumer = headers.new HeadersConsumer(maximumHeaderLength);
		PartialAsyncConsumer<ByteBuffer, Exception> consumer = headersConsumer.convert(
			ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), e -> e);
		request.setHeaders(headers);
		client.setAttribute(RECEIVE_CONSUMER_ATTRIBUTE, consumer);
		client.setAttribute(RECEIVE_STATUS_ATTRIBUTE, ReceiveStatus.HEADERS);
	}
	
	private boolean endOfHeaders(TCPServerClient client, HTTPRequest request, ByteBuffer data) {
		if (logger.trace())
			logger.trace("End of headers received");
		
		// Analyze headers to check if an upgrade of the protocol is requested
		try {
			if (handleUpgradeRequest(client, request, data))
				return false;
		} catch (Exception error) {
			receiveError(client, error, request);
			return false;
		}
		
		// check protocol version
		if (request.getProtocolVersion().getMajor() > 1 ||
			request.getProtocolVersion().getMajor() == 1 && request.getProtocolVersion().getMinor() > 1) {
			UnsupportedHTTPProtocolException e = new UnsupportedHTTPProtocolException(
				request.getProtocolVersion().getMajor() + "." + request.getProtocolVersion().getMinor());
			receiveError(client, e, request);
			return false;
		}

		RequestProcessingLimiter limiter = (RequestProcessingLimiter)client.getAttribute(LIMITER_ATTRIBUTE);
		HTTPRequestContext ctx = limiter.newResponse(client, request);
		
		boolean hasBody = request.isExpectingBody();
		
		if (!hasBody) {
			request.setEntity(new EmptyEntity(null, request.getHeaders()));
			endOfBody(client);
		}
		
		if (logger.trace())
			logger.trace("Processing request");
		try {
			processor.process(ctx);
		} catch (Exception e) {
			logger.error("HTTPRequestProcessor error", e);
			ctx.getErrorHandler().setError(ctx, HttpURLConnection.HTTP_INTERNAL_ERROR, "Internal server error", null);
		}
		
		if (!hasBody)
			return true;
		
		// by default, if not set, we use a binary entity
		if (request.getEntity() == null) {
			request.setEntity(new BinaryEntity(null, request.getHeaders()));
			if (logger.trace())
				logger.trace("Processor didn't set an entity into the request, default to a BinaryEntity");
		} else {
			if (logger.trace())
				logger.trace("Start receiving request body with entity set by processor: " + request.getEntity());
		}
		
		AsyncConsumer<ByteBuffer, IOException> bodyConsumer = request.getEntity().createConsumer(request.getHeaders().getContentLength());
		try {
			PartialAsyncConsumer<ByteBuffer, IOException> transfer =
				TransferEncodingFactory.create(request.getHeaders(), bodyConsumer);
			client.setAttribute(RECEIVE_CONSUMER_ATTRIBUTE, transfer);
			client.setAttribute(RECEIVE_STATUS_ATTRIBUTE, ReceiveStatus.BODY);
		} catch (IOException e) {
			receiveError(client, e, request);
			return false;
		}
		return true;
	}
	
	private static void endOfBody(TCPServerClient client) {
		client.setAttribute(REQUEST_END_RECEIVE_NANOTIME_ATTRIBUTE, Long.valueOf(System.nanoTime()));
		client.setAttribute(RECEIVE_STATUS_ATTRIBUTE, ReceiveStatus.START);
		client.removeAttribute(REQUEST_ATTRIBUTE);
		client.removeAttribute(RECEIVE_CONSUMER_ATTRIBUTE);
	}
	
	private class RequestProcessingLimiter {
		
		private boolean first = true;
		private LinkedList<HTTPServerResponse> responses = new LinkedList<>();
		
		private HTTPRequestContext newResponse(TCPServerClient client, HTTPRequest request) {
			HTTPServerResponse response;
			Async<IOException> previous;
			synchronized (responses) {
				previous = responses.isEmpty() ? new Async<>(true) : responses.getLast().getSent();
				response = new HTTPServerResponse();
				responses.add(response);
			}
			response.getSent().onDone(() -> {
				synchronized (responses) {
					responses.removeFirst();
				}
			});
			if (first) {
				first = false;
				if (!alternativeServices.isEmpty()) {
					StringBuilder alternatives = new StringBuilder();
					for (String alt : alternativeServices) {
						if (alternatives.length() > 0)
							alternatives.append(',');
						alternatives.append(alt);
					}
					alternatives.append("; ma=3600");
					response.getHeaders().addRawValue("Alt-Svc", alternatives.toString());
				}
			}
			HTTPRequestContext ctx = new HTTPRequestContext(client, request, response, errorHandler);
			AsyncTimeoutManager.timeout(response.getReady(), maximumRequestProcessingTime,
				() -> ctx.getErrorHandler().setError(ctx, HttpURLConnection.HTTP_GATEWAY_TIMEOUT, "Timeout", null));
			response.getReady().onDone(() -> previous.onSuccess(() -> sendResponse(ctx)));
			return ctx;
		}
		
		private void needMoreData(TCPServerClient client) {
			Async<IOException> ready = null;
			synchronized (responses) {
				if (responses.size() >= maximumPendingRequestsByClient)
					ready = responses.getFirst().getSent();
			}
			if (ready == null) {
				if (logger.trace())
					logger.trace("Wait for data from client");
				try { client.waitForData(receiveDataTimeout); }
				catch (ClosedChannelException e) { client.closed(); }
				return;
			}
			if (logger.trace())
				logger.trace("Wait for next response to be sent before to receive data from client");
			ready.onSuccess(() -> needMoreData(client));
		}
		
	}
	
	private boolean handleUpgradeRequest(TCPServerClient client, HTTPRequest request, ByteBuffer data)
	throws HTTPException {
		if (upgradableProtocols == null)
			return false;
		
		HTTP1ServerUpgradeProtocol proto = null;
		
		if (request.getHeaders().has(HTTPConstants.Headers.Request.UPGRADE)) {
			String conn = request.getHeaders().getFirstRawValue(HTTPConstants.Headers.Request.CONNECTION);
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
			String protoName = request.getHeaders().getFirstRawValue(HTTPConstants.Headers.Request.UPGRADE).trim().toLowerCase();
			proto = upgradableProtocols.get(protoName);

			// the protocol is supported
			if (proto != null && !proto.acceptUpgrade(client, request))
				return false;
		}
		
		boolean isCustomProtocol = false;
		if (proto == null)
			for (HTTP1ServerUpgradeProtocol p : upgradableProtocols.values())
				if (p.isUpgradeRequest(client, request, data)) {
					proto = p;
					isCustomProtocol = true;
					break;
				}
		
		if (proto == null)
			return false;
		
		// the protocol accepts the request, start it
		client.setAttribute(REQUEST_END_RECEIVE_NANOTIME_ATTRIBUTE, Long.valueOf(System.nanoTime()));
		client.setAttribute(UPGRADED_PROTOCOL_ATTRIBUTE, proto);
		if (!isCustomProtocol) {
			RequestProcessingLimiter limiter = (RequestProcessingLimiter)client.getAttribute(LIMITER_ATTRIBUTE);
			HTTPRequestContext ctx = limiter.newResponse(client, request);
			client.setAttribute(UPGRADED_PROTOCOL_REQUEST_CONTEXT_ATTRIBUTE, ctx);
		} else {
			client.removeAttribute(LIMITER_ATTRIBUTE);
		}
		logger.debug("Upgrading protocol to " + proto.getUpgradeProtocolToken());
		int recvTimeout = proto.startProtocol(client);
		client.removeAttribute(REQUEST_ATTRIBUTE);
		if (!isCustomProtocol)
			client.removeAttribute(UPGRADED_PROTOCOL_REQUEST_CONTEXT_ATTRIBUTE);
		if (data.hasRemaining()) {
			proto.dataReceivedFromClient(client, data);
		} else {
			bufferCache.free(data);
			if (recvTimeout >= 0)
				try { client.waitForData(recvTimeout); }
				catch (ClosedChannelException e) { client.closed(); }
		}
		return true;
	}
	
	private void sendResponse(HTTPRequestContext ctx) {
		HTTPServerResponse response = ctx.getResponse();
		if (response.getReady().isCancelled()) {
			ctx.getClient().close();
			return;
		}
		if (response.getStatusCode() < 100)
			response.setStatus(response.getReady().hasError() ? 500 : 200);

		if (enableRangeRequests)
			handleRangeRequest(ctx);
		
		MimeEntity entity = response.getEntity();
		AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> bodyProducer;
		if (entity != null)
			bodyProducer = entity.createBodyProducer();
		else
			bodyProducer = new AsyncSupplier<>(new Pair<>(Long.valueOf(0), new AsyncProducer.Empty<>()), null);

		if (response.getProtocolVersion() == null) {
			response.setProtocolVersion(ctx.getRequest().getProtocolVersion());
		}

		if (bodyProducer.isDone())
			sendResponse(ctx, bodyProducer);
		else
			bodyProducer.thenStart("Send HTTP response", Task.getCurrentPriority(),
				() -> sendResponse(ctx, bodyProducer), true);
	}
	
	private void sendResponse(
		HTTPRequestContext ctx,
		AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> bodyProducer
	) {
		HTTPServerResponse response = ctx.getResponse();
		if (bodyProducer.hasError()) {
			ctx.getErrorHandler().setError(ctx, 500, bodyProducer.getError().getMessage(), bodyProducer.getError());
			bodyProducer = response.getEntity().createBodyProducer();
		}
		
		Supplier<List<MimeHeader>> trailerSupplier = response.getTrailerHeadersSuppliers();
		Long bodySize = bodyProducer.getResult().getValue1();
		AsyncProducer<ByteBuffer, IOException> producer = bodyProducer.getResult().getValue2();

		boolean isChunked;
		if (response.isForceNoContent()) {
			isChunked = false;
			bodySize = Long.valueOf(0);
		} else {
			if (bodySize != null && trailerSupplier == null) {
				response.getHeaders().setContentLength(bodySize.longValue());
				isChunked = false;
			} else {
				response.getHeaders().setRawValue(MimeHeaders.TRANSFER_ENCODING, ChunkedTransfer.TRANSFER_NAME);
				response.getHeaders().remove(MimeHeaders.CONTENT_LENGTH);
				isChunked = true;
			}
		}

		if (logger.debug())
			logger.debug("Response code " + response.getStatusCode() + " for request "
				+ HTTP1RequestCommandProducer.generateString(ctx.getRequest()) + " to send "
				+ (isChunked ? "chunked" : "with Content-Length=" + bodySize));

		ByteArrayStringIso8859Buffer headers = new ByteArrayStringIso8859Buffer();
		headers.setNewArrayStringCapacity(4096);
		HTTP1ResponseStatusProducer.generate(response, headers);
		headers.append("\r\n");
		response.getHeaders().generateString(headers);
		ByteBuffer[] buffers = headers.asByteBuffers();
		if (logger.debug())
			logger.debug("Sending response with body size " + bodySize + " and headers:\n" + headers);
		AsyncConsumer<ByteBuffer, IOException> clientConsumer = ctx.getClient().asConsumer(3, sendDataTimeout);
		IAsync<IOException> sendHeaders = clientConsumer.push(Arrays.asList(buffers));
		
		sendHeaders.onError(error -> {
			if (logger.error())
				logger.error("Error sending HTTP response headers", error);
			ctx.getClient().close();
		});
		
		if (!isChunked && bodySize.longValue() == 0) {
			// empty body
			sendHeaders.onDone(response.getSent());
			if (!ctx.getRequest().isConnectionPersistent() || response.isForceClose())
				sendHeaders.onSuccess(ctx.getClient()::close);
			return;
		}
		if (isChunked)
			clientConsumer = new ChunkedTransfer.Sender(clientConsumer, trailerSupplier);

		AsyncConsumer<ByteBuffer, IOException> consumer = clientConsumer;
		sendHeaders.thenStart("Send HTTP response body", Task.getCurrentPriority(), () -> {
			if (logger.trace())
				logger.trace("Headers sent, start to send response body");
			Async<IOException> sendBody = producer.toConsumer(consumer, "Send HTTP response body", Task.getCurrentPriority());
			sendBody.onError(error -> {
				if (logger.error())
					logger.error("Error sending HTTP response body", error);
				ctx.getClient().close();
			});
			sendBody.onDone(response.getSent());
			if (!ctx.getRequest().isConnectionPersistent() || response.isForceClose())
				sendBody.onSuccess(ctx.getClient()::close);
		}, false);
	}
	
	@Override
	public LinkedList<ByteBuffer> prepareDataToSend(TCPServerClient client, List<ByteBuffer> data) {
		if (data instanceof LinkedList)
			return (LinkedList<ByteBuffer>)data;
		return new LinkedList<>(data);
	}
	
	/**
	 * By default range requests are disabled. It may be enabled globally by calling the method
	 * {@link #enableRangeRequests()} or by calling this method only on the requests we want to enable it.
	 */
	private void handleRangeRequest(HTTPRequestContext ctx) {
		HTTPServerResponse response = ctx.getResponse();
		if (response.getStatusCode() != 200) return;
		if (!response.getEntity().canProduceBodyRange()) return;
		
		response.setHeader(HTTPConstants.Headers.Response.ACCEPT_RANGES, HTTPConstants.Headers.Response.ACCEPT_RANGES_VALUE_BYTES);
			
		MimeHeader rangeHeader = ctx.getRequest().getHeaders().getFirst(HTTPConstants.Headers.Request.RANGE);
		if (rangeHeader == null) return;
		CharArrayString rangeStr = new CharArrayString(rangeHeader.getRawValue());
		rangeStr.trim();
		if (!rangeStr.startsWith("bytes=")) return;
		rangeStr = rangeStr.substring(6);
		rangeStr.trim();
		List<RangeLong> ranges = parseRanges(rangeStr);
		if (ranges == null) {
			logger.error("Invalid range request: " + rangeHeader.getRawValue());
			return;
		}
		
		if (ranges.size() == 1) {
			Triple<RangeLong, Long, BinaryEntity> subEntity = response.getEntity().createBodyRange(ranges.get(0));
			if (subEntity == null)
				return;
			response.setStatus(206);
			response.setHeader(HTTPConstants.Headers.Response.CONTENT_RANGE,
				subEntity.getValue1().min + "-" + subEntity.getValue1().max + "/" + subEntity.getValue2());
			response.setEntity(subEntity.getValue3());
			return;
		}
		
		// multipart
		MultipartEntity multipart = new MultipartEntity("byteranges");
		for (MimeHeader h : response.getHeaders().getHeaders())
			if (!h.getNameLowerCase().startsWith("content-"))
				multipart.getHeaders().add(h);
		for (RangeLong range : ranges) {
			Triple<RangeLong, Long, BinaryEntity> subEntity = response.getEntity().createBodyRange(range);
			if (subEntity == null)
				return;
			BinaryEntity part = subEntity.getValue3();
			part.setHeader(HTTPConstants.Headers.Response.CONTENT_RANGE,
				subEntity.getValue1().min + "-" + subEntity.getValue1().max + "/" + subEntity.getValue2());
			multipart.add(part);
		}
		response.setStatus(206);
		response.setEntity(multipart);
	}
	
	private static List<RangeLong> parseRanges(CharArrayString str) {
		List<RangeLong> ranges = new LinkedList<>();
		for (CharArrayString s : str.split(',')) {
			int i = s.indexOf('-');
			if (i < 0) return null;
			CharArrayString minStr = s.substring(0, i);
			minStr.trim();
			CharArrayString maxStr = s.substring(i + 1);
			maxStr.trim();
			long start;
			if (minStr.length() == 0) {
				start = -1;
			} else {
				try { start = Long.parseLong(minStr.asString()); }
				catch (NumberFormatException e) { return null; }
				if (start < 0) return null;
			}
			long end;
			if (maxStr.length() == 0) {
				end = -1;
			} else {
				try { end = Long.parseLong(maxStr.asString()); }
				catch (NumberFormatException e) { return null; }
				if (end < 0) return null;
			}
			if (start == -1 && end == -1)
				return null;
			ranges.add(new RangeLong(start, end));
		}
		return ranges;
	}
	
}
