package net.lecousin.framework.network.http1.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.Executable;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.concurrent.util.PartialAsyncConsumer;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientConnection;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http.client.HTTPClientResponse;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http1.HTTP1RequestCommandProducer;
import net.lecousin.framework.network.http1.HTTP1ResponseStatusConsumer;
import net.lecousin.framework.network.mime.entity.DefaultMimeEntityFactory;
import net.lecousin.framework.network.mime.entity.EmptyEntity;
import net.lecousin.framework.network.mime.entity.MimeEntity;
import net.lecousin.framework.network.mime.entity.MimeEntityFactory;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.transfer.ChunkedTransfer;
import net.lecousin.framework.network.mime.transfer.TransferEncodingFactory;
import net.lecousin.framework.text.ByteArrayStringIso8859Buffer;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.Triple;

/** HTTP/1 connection that supports queuing requests. */
public class HTTP1ClientConnection extends HTTPClientConnection {

	/** Open a connection, possibly through a proxy, to the given HTTP/1 server.
	 * It returns the TCPClient (which may be a SSLClient), the connection result, and a boolean indicating
	 * if the connection is done through a proxy. 
	 * @throws URISyntaxException if the path is relative
	 */
	public static Triple<? extends TCPClient, IAsync<IOException>, Boolean> openConnection(
		String hostname, int port, boolean isSecure, String path, HTTPClientConfiguration config, Logger logger
	) throws URISyntaxException {
		if (port <= 0) port = isSecure ? HTTPConstants.DEFAULT_HTTPS_PORT : HTTPConstants.DEFAULT_HTTP_PORT;
		ProxySelector proxySelector = config.getProxySelector();
		if (proxySelector == null)
			return triple(openDirectConnection(new InetSocketAddress(hostname, port), hostname, isSecure, config, logger), false);
		URI uri = new URI(isSecure ? HTTPConstants.HTTPS_SCHEME : HTTPConstants.HTTP_SCHEME, null, hostname, port, path, null, null);
		List<Proxy> proxies = proxySelector.select(uri);
		Proxy proxy = null;
		for (Proxy p : proxies) {
			switch (p.type()) {
			case DIRECT:
				return triple(openDirectConnection(new InetSocketAddress(hostname, port), hostname, isSecure, config, logger), false);
			case HTTP:
				if (proxy == null && p.address() instanceof InetSocketAddress)
					proxy = p;
				break;
			default: break;
			}
		}
		if (proxy == null)
			return triple(openDirectConnection(new InetSocketAddress(hostname, port), hostname, isSecure, config, logger), false);
		return triple(openTunnelOnProxy((InetSocketAddress)proxy.address(), hostname, port, isSecure, config, logger), true);
	}
	
	private static Triple<? extends TCPClient, IAsync<IOException>, Boolean> triple(
		Pair<? extends TCPClient, IAsync<IOException>> pair, boolean isUsingProxy
	) {
		return new Triple<>(pair.getValue1(), pair.getValue2(), Boolean.valueOf(isUsingProxy));
	}
	
	/** Open a direct connection to a HTTP/1 server. */
	public static Pair<? extends TCPClient, IAsync<IOException>> openDirectConnection(
		InetSocketAddress serverAddress, String hostname, boolean isSecure, HTTPClientConfiguration config, Logger logger
	) {
		if (isSecure)
			return openDirectSSLConnection(serverAddress, hostname, config, logger);
		return openDirectConnection(serverAddress, config, logger);
	}
	
	/** Open a direct connection to a HTTP/1 server. */
	public static Pair<TCPClient, IAsync<IOException>> openDirectConnection(
		InetSocketAddress serverAddress, HTTPClientConfiguration config, Logger logger
	) {
		if (logger.debug())
			logger.debug("Open direct clear connection to HTTP server " + serverAddress);
		TCPClient client = new TCPClient();
		Async<IOException> connect = client.connect(
			serverAddress, config.getTimeouts().getConnection(), config.getSocketOptionsArray());
		return new Pair<>(client, connect);
	}
	
	/** Open a direct connection to a HTTP/1 server. */
	public static Pair<SSLClient, IAsync<IOException>> openDirectSSLConnection(
		InetSocketAddress serverAddress, String hostname, HTTPClientConfiguration config, Logger logger
	) {
		if (logger.debug())
			logger.debug("Open direct SSL connection to HTTPS server " + serverAddress);
		SSLClient client = new SSLClient(config.getSSLContext());
		client.setHostNames(hostname);
		Async<IOException> connect = client.connect(
			serverAddress, config.getTimeouts().getConnection(), config.getSocketOptionsArray());
		return new Pair<>(client, connect);
	}
	
	/** Open a tunnel on HTTP/1 proxy server. */
	public static Pair<TCPClient, IAsync<IOException>> openTunnelOnProxy(
		InetSocketAddress proxyAddress,
		String targetHostname, int targetPort, boolean isSecure,
		HTTPClientConfiguration config, Logger logger
	) {
		if (logger.debug())
			logger.debug("Open connection to HTTP" + (isSecure ? "S" : "") + " server " + targetHostname + ":" + targetPort
				+ " through proxy " + proxyAddress);
		
		Pair<TCPClient, IAsync<IOException>> proxyConnection = openDirectConnection(proxyAddress, config, logger);
		
		// prepare the CONNECT request
		String host = targetHostname + ":" + targetPort;
		HTTPRequest connectRequest = new HTTPRequest()
			.setMethod(HTTPRequest.METHOD_CONNECT).setEncodedPath(new ByteArrayStringIso8859Buffer(host));
		connectRequest.addHeader(HTTPConstants.Headers.Request.HOST, host);
		ByteArrayStringIso8859Buffer headers = new ByteArrayStringIso8859Buffer();
		headers.setNewArrayStringCapacity(512);
		HTTP1RequestCommandProducer.generate(connectRequest, headers);
		headers.append("\r\n");
		connectRequest.getHeaders().generateString(headers);
		ByteBuffer[] buffers = headers.asByteBuffers();
		
		// prepare SSL client
		TCPClient client;
		if (isSecure) {
			@SuppressWarnings("resource")
			SSLClient sslClient = new SSLClient(config.getSSLContext());
			sslClient.setHostNames(targetHostname);
			client = sslClient;
		} else {
			client = proxyConnection.getValue1();
		}
		
		Async<IOException> connect = new Async<>();
		
		proxyConnection.getValue2().onDone(() ->
		proxyConnection.getValue1().asConsumer(2, config.getTimeouts().getSend()).push(Arrays.asList(buffers)).onDone(() -> {
			// once headers are sent, receive the response status line
			HTTPResponse response = new HTTPResponse();
			proxyConnection.getValue1().getReceiver().consume(
				new HTTP1ResponseStatusConsumer(response)
				.convert(ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), IO::error),
				4096, config.getTimeouts().getReceive())
			.onDone(() -> {
				// status line received
				if (!response.isSuccess()) {
					connect.error(new HTTPResponseError(response));
					return;
				}
				// read headers
				MimeHeaders responseHeaders = new MimeHeaders();
				response.setHeaders(responseHeaders);
				proxyConnection.getValue1().getReceiver().consume(
					responseHeaders.createConsumer(config.getLimits().getHeadersLength())
					.convert(ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), IO::error),
					4096, config.getTimeouts().getReceive())
				.onDone(() -> {
					// headers received
					// tunnel connection established
					if (isSecure) {
						// replace connection of SSLClient by the tunnel
						if (logger.debug())
							logger.debug("Tunnel open through proxy " + proxyAddress + ", start SSL handshake");
						((SSLClient)client).tunnelConnected(proxyConnection.getValue1(), connect,
							config.getTimeouts().getReceive());
					} else {
						connect.unblock();
					}
				}, connect);
			}, connect);
		}, connect), connect);
		return new Pair<>(client, connect);
	}

	
	/** Constructor. */
	public HTTP1ClientConnection(int maxPendingRequests, HTTPClientConfiguration config) {
		this.maxPendingRequests = maxPendingRequests;
		this.config = config;
		this.logger = LCCore.getApplication().getLoggerFactory().getLogger(HTTP1ClientConnection.class);
	}

	/** Constructor. */
	public HTTP1ClientConnection(TCPClient client, IAsync<IOException> connect, int maxPendingRequests, HTTPClientConfiguration config) {
		this(maxPendingRequests, config);
		setConnection(client, connect);
	}
	
	private Logger logger;
	private int maxPendingRequests;
	private HTTPClientConfiguration config;
	private AsyncConsumer<ByteBuffer, IOException> clientConsumer;
	private LinkedList<Request> requests = new LinkedList<>();
	private long idleStart = -1;
	
	private static class Request {
		
		private HTTPClientRequestContext ctx;
		private AsyncSupplier<ByteBuffer[], NoException> headers;
		private Async<IOException> headersSent;
		private IAsync<IOException> receiveStatusLine;
		private IAsync<IOException> receiveHeaders;
		private boolean nextRequestCanBeSent = false;
		private AsyncSupplier<Boolean, NoException> result;
		
	}
	
	@Override
	public void setConnection(TCPClient tcp, IAsync<IOException> connect) {
		super.setConnection(tcp, connect);
		connect.onDone(() -> {
			if (connect.isSuccessful())
				 clientConsumer = tcp.asConsumer(3, config.getTimeouts().getSend());
			else
				stopping = true;
			doNextJob();
		});
		tcp.onclosed(() -> {
			stopping = true;
			doNextJob();
		});
	}
	
	@Override
	public boolean hasPendingRequest() {
		return !requests.isEmpty();
	}

	@Override
	public boolean isAvailableForReuse() {
		return requests.size() < maxPendingRequests;
	}
	
	@Override
	public long getIdleTime() {
		return idleStart;
	}
	
	@Override
	public String getDescription() {
		StringBuilder s = new StringBuilder(128);
		if (idleStart > 0) {
			s.append("idle since ").append((System.currentTimeMillis() - idleStart) / 1000).append("s.");
			return s.toString();
		}
		synchronized (requests) {
			s.append(requests.size()).append(" pending requests");
			if (!requests.isEmpty()) {
				Request r = requests.getFirst();
				s.append(", first: ");
				if (!r.headersSent.isDone())
					s.append("waiting to send headers");
				else if (r.ctx.getRequestBody() != null)
					s.append("waiting to send body");
				else if (r.receiveStatusLine == null || !r.receiveStatusLine.isDone())
					s.append("waiting for response status line");
				else if (r.receiveHeaders == null || !r.receiveHeaders.isDone())
					s.append("waiting for response headers");
				else if (!r.ctx.getResponse().getBodyReceived().isDone())
					s.append("waiting for response body");
				else if (!r.ctx.getResponse().getTrailersReceived().isDone())
					s.append("waiting for response trailers");
				else
					s.append("done");
			}
		}
		return s.toString();
	}

	@Override
	public void reserve(HTTPClientRequestContext reservedFor) {
		Request r = new Request();
		r.ctx = reservedFor;
		synchronized (requests) {
			requests.add(r);
		}
	}

	@Override
	public AsyncSupplier<Boolean, NoException> sendReserved(HTTPClientRequestContext ctx) {
		synchronized (requests) {
			if (stopping)
				return new AsyncSupplier<>(Boolean.FALSE, null);
			for (Request r : requests) {
				if (r.ctx == ctx) {
					r.result = new AsyncSupplier<>();
					prepareHeaders(r);
					return r.result;
				}
			}
		}
		return new AsyncSupplier<>(Boolean.FALSE, null);
	}
	
	@Override
	public void send(HTTPClientRequestContext ctx) {
		ctx.setSender(this);
		ctx.applyFilters(config.getFilters());
		
		HTTPClientRequest req = ctx.getRequest();
		if (connect == null) {
			try {
				Triple<? extends TCPClient, IAsync<IOException>, Boolean> conn = openConnection(
					req.getHostname(), req.getPort(), req.isSecure(), req.getEncodedPath().asString(),
					config, logger);
				ctx.setThroughProxy(conn.getValue3().booleanValue());
				setConnection(conn.getValue1(), conn.getValue2());
			} catch (Exception e) {
				ctx.getRequestSent().error(IO.error(e));
			}
		}
		
		// start to prepare request while connecting
		if (ctx.getRequestBody() == null) {
			AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> bodyProducer =
				ctx.prepareRequestBody();
				
			bodyProducer.thenStart("Prepare HTTP request", Task.getCurrentPriority(), (Task<Void, NoException> t) -> {
				ctx.setRequestBody(bodyProducer.getResult());
				requestBodyReady(ctx);
				return null;
			}, ctx.getRequestSent());
		} else {
			requestBodyReady(ctx);
		}
	}
	
	/** Send a request and receive the response. */
	public static HTTPClientResponse send(
		HTTPClientRequest request, int maxRedirect, MimeEntityFactory entityFactory, HTTPClientConfiguration config
	) {
		HTTP1ClientConnection conn = new HTTP1ClientConnection(1, config);
		HTTPClientRequestContext ctx = new HTTPClientRequestContext(conn, request);
		ctx.setMaxRedirections(maxRedirect);
		ctx.setEntityFactory(entityFactory);
		conn.send(ctx);
		ctx.getResponse().getTrailersReceived().onDone(conn::close);
		return ctx.getResponse();
	}

	/** Send a request and receive the response. */
	@SuppressWarnings("java:S2095") // conn is closed
	public static HTTPClientResponse send(HTTPClientRequest request, HTTPClientConfiguration config) {
		HTTP1ClientConnection conn = new HTTP1ClientConnection(1, config);
		HTTPClientResponse response = conn.send(request);
		response.getTrailersReceived().onDone(conn::close);
		return response;
	}

	@Override
	public void redirectTo(HTTPClientRequestContext ctx, URI targetUri) {
		if (!stopping && isCompatible(targetUri, tcp, ctx.getRequest().getHostname(), ctx.getRequest().getPort())) {
			if (logger.debug()) logger.debug("Reuse same connection for redirection to " + targetUri);
			send(ctx);
			return;
		}
		if (logger.debug()) logger.debug("Open new connection for redirection to " + targetUri);
		Triple<? extends TCPClient, IAsync<IOException>, Boolean> newClient;
		try {
			newClient = openConnection(targetUri.getHost(), targetUri.getPort(),
				HTTPConstants.HTTPS_SCHEME.equalsIgnoreCase(targetUri.getScheme()),
				targetUri.getPath(), config, logger);
		} catch (Exception e) {
			ctx.getRequestSent().error(IO.error(e));
			return;
		}
		HTTP1ClientConnection newConn = new HTTP1ClientConnection(newClient.getValue1(), newClient.getValue2(), 1, config);
		ctx.setThroughProxy(newClient.getValue3().booleanValue());
		newConn.send(ctx);
		ctx.getResponse().getTrailersReceived().onDone(newConn::close);
	}

	private void requestBodyReady(HTTPClientRequestContext ctx) {
		Long size = ctx.getRequestBody().getValue1();
		Supplier<List<MimeHeader>> trailerSupplier = ctx.getRequest().getTrailerHeadersSuppliers();
		
		if (size == null || trailerSupplier != null) {
			ctx.getRequest().setHeader(MimeHeaders.TRANSFER_ENCODING, ChunkedTransfer.TRANSFER_NAME);
		} else if (size.longValue() > 0 || HTTPRequest.methodMayContainBody(ctx.getRequest().getMethod())) {
			ctx.getRequest().getHeaders().setContentLength(size.longValue());
		}
		
		Request r = new Request();
		r.ctx = ctx;
		r.result = new AsyncSupplier<>();
		prepareHeaders(r);
		synchronized (requests) {
			requests.add(r);
		}
		doNextJob();
		r.result.onDone(() -> {
			if (!r.result.getResult().booleanValue())
				ctx.getRequestSent().cancel(new CancelException("Unable to send request"));
		});
	}
	
	private void prepareHeaders(Request r) {
		r.headers = Task.cpu("Prepare HTTP Request headers", Priority.NORMAL, (Task<ByteBuffer[], NoException> t) -> {
			HTTPRequest request = r.ctx.getRequest();
			ByteArrayStringIso8859Buffer headers = new ByteArrayStringIso8859Buffer();
			headers.setNewArrayStringCapacity(4096);
			HTTP1RequestCommandProducer.generate(request, headers);
			headers.append("\r\n");
			request.getHeaders().generateString(headers);
			r.ctx.getRequestSent().onDone(this::doNextJob);
			return headers.asByteBuffers();
		}).start().getOutput();
		r.headers.onDone(this::doNextJob);
	}
	
	private Task<Void, NoException> nextJobTask = null;
	private Task<Void, NoException> currentJobTask = null;
	private Object nextJobTaskLock = new Object();
	
	private void doNextJob() {
		synchronized (nextJobTaskLock) {
			if (nextJobTask != null)
				return;
			nextJobTask = Task.cpu("Process HTTP/1 client requests", Priority.NORMAL, (Task<Void, NoException> t) -> {
				synchronized (nextJobTaskLock) {
					currentJobTask = t;
					nextJobTask = null;
				}
				synchronized (requests) {
					if (!connect.isDone())
						return null;
					nextJob();
				}
				return null;
			});
			if (currentJobTask != null)
				nextJobTask.startAfter(currentJobTask);
			else
				nextJobTask.start();
		}
	}
	
	@SuppressWarnings("java:S3776") // complexity
	private void nextJob() {
		Request r = null;
		Request previous = null;
		for (Iterator<Request> it = requests.iterator(); it.hasNext(); previous = r) {
			r = it.next();
			// if only reserved, do nothing
			if (r.result == null)
				break;
			
			// if stopping, cancel requests
			if (stopping) {
				if (!r.result.isDone())
					unblockResult(r, Boolean.FALSE);
				it.remove();
				continue;
			}
			
			// if headers are not ready, do nothing
			if (!r.headers.isDone())
				break;

			// if we don't know yet if a subsequent request can be sent, wait 
			if (previous != null && (!previous.nextRequestCanBeSent || !previous.ctx.getRequestSent().isDone()))
				break;

			// if headers are not yet sent, send them
			if (r.headersSent == null) {
				if (logger.debug()) logger.debug("Send headers for request " + r.ctx + " to " + tcp);
				sendHeaders(r);
				unblockResult(r, Boolean.TRUE);
				break;
			}
			
			// if we are still sending headers, wait
			if (!r.headersSent.isDone()) {
				if (logger.debug()) logger.debug("Wait for headers to be sent");
				break;
			}
			
			// if sending headers failed, stop
			if (!r.headersSent.isSuccessful()) {
				stopping = true;
				if (logger.debug()) logger.debug("Error sending headers, stop connection", r.headersSent.getError());
				final Request req = r;
				Task.cpu("Error sending request", Priority.RATHER_IMPORTANT, t -> {
					req.headersSent.forwardIfNotSuccessful(req.ctx.getRequestSent());
					return null;
				}).start();
				it.remove();
				continue; // cancel remaining pending requests
			}
			
			// if we didn't start to send body yet
			if (r.ctx.getRequestBody() != null) {
				Pair<Long, AsyncProducer<ByteBuffer, IOException>> body = r.ctx.getRequestBody();
				r.ctx.setRequestBody(null);
				// send body
				if (logger.debug()) logger.debug("Send body for request " + r.ctx + " to " + tcp);
				final Request req = r;
				final Request prev = previous;
				Task.cpu("Send HTTP/1 request body",  Priority.NORMAL, t -> {
					if (!req.ctx.getRequest().isExpectingBody() ||
						(body.getValue1() != null && body.getValue1().longValue() == 0)) {
						req.ctx.getRequestSent().unblock();
						if (prev == null) {
							Task.cpu("Received HTTP/1 response", Priority.NORMAL, task -> {
								receiveResponse(req);
								return null;
							}).start();
						} else {
							prev.ctx.getResponse().getTrailersReceived().onSuccess(() -> receiveResponse(req));
						}
						return null;
					}

					AsyncConsumer<ByteBuffer, IOException> consumer = 
						body.getValue1() == null || req.ctx.getRequest().getTrailerHeadersSuppliers() != null
						? new ChunkedTransfer.Sender(clientConsumer, req.ctx.getRequest().getTrailerHeadersSuppliers())
						: clientConsumer;
					Async<IOException> sendBody = body.getValue2()
						.toConsumer(consumer, "Send HTTP request body", Task.getCurrentPriority());
					if (prev == null) {
						receiveResponse(req);
					} else {
						prev.ctx.getResponse().getTrailersReceived().onSuccess(() -> receiveResponse(req));
					}
					sendBody.onErrorOrCancel(() -> {
						sendBody.forwardIfNotSuccessful(req.ctx.getRequestSent());
						stop(req, true);
					});
					sendBody.onSuccess(req.ctx.getRequestSent()::unblock);
					return null;
				}).start();
			}
		}
		
		if (requests.isEmpty()) {
			int idle = config.getTimeouts().getIdle();
			if (idle <= 0) {
				stopping = true;
				tcp.close();
			} else {
				idleStart = System.currentTimeMillis();
				Task.cpu("Check HTTP1ClientConnection idle", new CheckIdle()).executeAt(idleStart + idle).start();
			}
		} else {
			idleStart = -1;
		}
	}
	
	private static void unblockResult(Request r, Boolean result) {
		Task.cpu("Signal HTTP/1 request handling", Priority.RATHER_IMPORTANT, t -> {
			r.result.unblockSuccess(result);
			return null;
		}).start();
	}
	
	private class CheckIdle implements Executable<Void, NoException> {
		@Override
		public Void execute(Task<Void, NoException> taskContext) {
			synchronized (requests) {
				if (idleStart == -1)
					return null;
				int idle = config.getTimeouts().getIdle();
				if (idle <= 0)
					return null;
				if (System.currentTimeMillis() - idleStart < idle)
					return null;
				stopping = true;
			}
			tcp.close();
			return null;
		}
	}
	
	private void sendHeaders(Request r) {
		r.headersSent = new Async<>();
		Task.cpu("Send HTTP Request headers", Priority.NORMAL, t -> {
			IAsync<IOException> send = clientConsumer.push(Arrays.asList(r.headers.getResult()));
			send.onDone(r.headersSent);
			return null;
		}).start().getOutput().onError(err -> r.headersSent.error(IO.error(err)));
		r.headersSent.onDone(HTTP1ClientConnection.this::doNextJob);
	}
	
	private void stop(Request r, boolean andClose) {
		stopping = true;
		synchronized (requests) {
			requests.remove(r);
		}
		if (andClose)
			tcp.close();
		doNextJob();
	}
	
	private void requestDone(Request r) {
		synchronized (requests) {
			requests.remove(r);
		}
		doNextJob();
	}
	
	public static HTTPClientResponse receiveResponse(TCPClient client, HTTPClientRequest requestSent, HTTPClientConfiguration config) {
		HTTP1ClientConnection c = new HTTP1ClientConnection(client, new Async<>(true), 1, config);
		Request r = new Request();
		r.ctx = new HTTPClientRequestContext(c, requestSent);
		c.receiveResponse(r);
		return r.ctx.getResponse();
	}
	
	private void receiveResponse(Request r) {
		PartialAsyncConsumer<ByteBuffer, IOException> statusLineConsumer = new HTTP1ResponseStatusConsumer(r.ctx.getResponse()).convert(
			ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), IO::error);
		r.receiveStatusLine = tcp.getReceiver().consume(statusLineConsumer, 4096, config.getTimeouts().getReceive());
		r.ctx.getResponse().setHeaders(new MimeHeaders());
		if (r.receiveStatusLine.isDone())
			reveiceResponseHeaders(r);
		else
			r.receiveStatusLine.thenStart("Receive HTTP response", Task.getCurrentPriority(), () -> reveiceResponseHeaders(r), true);
	}
	
	private void reveiceResponseHeaders(Request r) {
		if (r.receiveStatusLine.forwardIfNotSuccessful(r.ctx.getResponse().getHeadersReceived())) {
			if (logger.debug()) logger.debug("Receive status line error, stop connection", r.receiveStatusLine.getError());
			stop(r, true);
			return;
		}
		if (logger.debug()) logger.debug("Status line received: " + r.ctx.getResponse().getStatusCode() + " for " + r.ctx + " from" + tcp);
		if (r.ctx.getOnStatusReceived() != null) {
			Boolean close = r.ctx.getOnStatusReceived().apply(r.ctx.getResponse());
			if (close != null) {
				stop(r, close.booleanValue());
				return;
			}
		}
		PartialAsyncConsumer<ByteBuffer, IOException> headersConsumer = r.ctx.getResponse().getHeaders()
			.createConsumer(config.getLimits().getHeadersLength()).convert(
					ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), IO::error);
		r.receiveHeaders = tcp.getReceiver().consume(headersConsumer, 4096, config.getTimeouts().getReceive());
		if (r.receiveHeaders.isDone())
			reveiceResponseBody(r);
		else
			r.receiveHeaders.thenStart("Receive HTTP response", Task.getCurrentPriority(), () -> reveiceResponseBody(r), true);
	}

	private void reveiceResponseBody(Request r) {
		if (r.receiveHeaders.forwardIfNotSuccessful(r.ctx.getResponse().getHeadersReceived())) {
			if (logger.debug()) logger.debug("Receive headers error, stop connection", r.receiveHeaders.getError());
			stop(r, true);
			return;
		}
		if (logger.debug()) logger.debug("Headers received for " + r.ctx + " from " + tcp);
		HTTPClientResponse response = r.ctx.getResponse();
		if (r.ctx.getOnHeadersReceived() != null) {
			Boolean close = r.ctx.getOnHeadersReceived().apply(response);
			if (close != null) {
				stop(r, close.booleanValue());
				return;
			}
		}
		
		if (handleHeaders(r))
			return;
		
		r.ctx.getResponse().getHeadersReceived().unblock();
		
		if (response.isConnectionClose()) {
			stopping = true;
		} else {
			r.nextRequestCanBeSent = true;
		}

		Long size = response.getHeaders().getContentLength();
		PartialAsyncConsumer<ByteBuffer, IOException> transfer;
		try {
			if (response.isBodyExpected()) {
				MimeEntityFactory entityFactory = r.ctx.getEntityFactory();
				if (entityFactory == null) entityFactory = DefaultMimeEntityFactory.getInstance();
				MimeEntity entity = entityFactory.create(null, response.getHeaders());
				transfer = TransferEncodingFactory.create(response.getHeaders(),
					entity.createConsumer(response.getHeaders().getContentLength()));
				response.setEntity(entity);
				r.ctx.getResponse().getHeadersReceived().unblock();
			} else {
				if (logger.debug()) logger.debug("No body expected, end of request");
				response.setEntity(new EmptyEntity(null, response.getHeaders()));
				requestDone(r);
				r.ctx.getResponse().getHeadersReceived().unblock();
				r.ctx.getResponse().getBodyReceived().unblock();
				r.ctx.getResponse().getTrailersReceived().unblock();
				doNextJob();
				return;
			}
		} catch (Exception e) {
			stop(r, true);
			r.ctx.getResponse().getBodyReceived().error(IO.error(e));
			return;
		}
		if (logger.debug()) logger.debug("Start receiving body for " + r.ctx + " from " + tcp);
		int bufferSize;
		if (size == null)
			bufferSize = 8192;
		else if (size.longValue() <= 1024)
			bufferSize = 1024;
		else if (size.longValue() <= 64 * 1024)
			bufferSize = size.intValue();
		else
			bufferSize = 64 * 1024;
		IAsync<IOException> receiveBody = tcp.getReceiver().consume(transfer, bufferSize, config.getTimeouts().getReceive());
		receiveBody.onSuccess(() -> {
			if (logger.debug()) logger.debug("Body received, end of request " + r.ctx + " from " + tcp);
			requestDone(r);
			r.ctx.getResponse().getBodyReceived().unblock();
			r.ctx.getResponse().getTrailersReceived().unblock();
		});
		receiveBody.onErrorOrCancel(() -> {
			stop(r, true);
			receiveBody.forwardIfNotSuccessful(r.ctx.getResponse().getBodyReceived());
		});
		doNextJob();
	}
	
	private boolean handleHeaders(Request r) {
		try {
			HTTPClient.addKnowledgeFromResponseHeaders(r.ctx.getRequest(), r.ctx.getResponse(),
				(InetSocketAddress)tcp.getRemoteAddress(), r.ctx.isThroughProxy());
		} catch (Exception e) {
			logger.error("Unexpected error", e);
		}
		
		return handleRedirection(r);
	}
	
	private boolean handleRedirection(Request r) {
		HTTPClientResponse response = r.ctx.getResponse();
		if (!HTTPResponse.isRedirectionStatusCode(response.getStatusCode()))
			return false;
		if (r.ctx.getMaxRedirections() <= 0) {
			if (logger.debug()) logger.debug("No more redirection allowed, handle the response");
			return false;
		}
		
		String location = response.getHeaders().getFirstRawValue(HTTPConstants.Headers.Response.LOCATION);
		if (location == null) {
			if (logger.warn()) logger.warn("No location given for redirection");
			return false;
		}
		
		Async<IOException> skipBody = null;
		if (response.isConnectionClose()) {
			stop(r, true);
		} else {
			Long size = response.getHeaders().getContentLength();
			if (size != null && size.longValue() > 0 && size.longValue() < 65536) {
				skipBody = tcp.getReceiver().skipBytes(size.intValue(), config.getTimeouts().getReceive());
				r.nextRequestCanBeSent = true;
				if (logger.debug()) logger.debug("Skip body of redirection from " + r.ctx + " on " + tcp);
				skipBody.onSuccess(() -> requestDone(r));
				skipBody.onErrorOrCancel(() -> stop(r, true));
			} else if (size != null && size.longValue() == 0) {
				r.nextRequestCanBeSent = true;
				requestDone(r);
			} else {
				if (logger.debug()) logger.debug("Stop connection to avoid receiving body before redirection");
				stop(r, true);
			}
		}
		if (skipBody == null)
			skipBody = new Async<>(true);
		
		if (logger.debug()) logger.debug("Redirect to " + location);
		Task.cpu("Redirect HTTP request", Priority.NORMAL, t -> {
			try {
				r.ctx.redirectTo(location);
			} catch (URISyntaxException e) {
				IOException error = new IOException("Invalid redirect location: " + location, e);
				r.ctx.getResponse().getHeadersReceived().error(error);
			}
			return null;
		}).startOn(skipBody, true);
		return true;
	}

}
