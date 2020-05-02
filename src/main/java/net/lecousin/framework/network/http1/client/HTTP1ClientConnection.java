package net.lecousin.framework.network.http1.client;

import java.io.IOException;
import java.net.InetSocketAddress;
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
import net.lecousin.framework.network.http.client.HTTPClientConfiguration.Protocol;
import net.lecousin.framework.network.http.client.HTTPClientConnection;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http.client.HTTPClientResponse;
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

/** HTTP/1 connection that supports queuing requests. */
public class HTTP1ClientConnection extends HTTPClientConnection {
	
	/** Constructor. */
	public HTTP1ClientConnection(int maxPendingRequests, HTTPClientConfiguration config) {
		this.maxPendingRequests = maxPendingRequests;
		this.config = config;
		config.getAllowedProtocols().retainAll(Arrays.asList(Protocol.HTTP1, Protocol.HTTP1S));
		this.logger = LCCore.getApplication().getLoggerFactory().getLogger(HTTP1ClientConnection.class);
	}

	/** Constructor. */
	public HTTP1ClientConnection(
		TCPClient client, Async<IOException> connect, boolean isThroughProxy, int maxPendingRequests, HTTPClientConfiguration config
	) {
		this(maxPendingRequests, config);
		setConnection(client, connect, isThroughProxy);
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
	public void setConnection(TCPClient tcp, Async<IOException> connect, boolean isThroughProxy) {
		super.setConnection(tcp, connect, isThroughProxy);
		clientConsumer = tcp.asConsumer(3, config.getTimeouts().getSend());
		connect.onDone(() -> {
			if (!connect.isSuccessful())
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
				OpenConnection conn = openConnection(req.getHostname(), req.getPort(), req.getEncodedPath().asString(),
					req.isSecure(), config, logger);
				ctx.setThroughProxy(conn.isThroughProxy());
				setConnection(conn.getClient(), conn.getConnect(), conn.isThroughProxy());
			} catch (Exception e) {
				ctx.getRequestSent().error(IO.error(e));
			}
		}
		
		// start to prepare request while connecting
		if (ctx.getRequestBody() == null) {
			AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> bodyProducer =
				ctx.prepareRequestBody();
				
			bodyProducer.thenStart(Task.cpu("Prepare HTTP request", Task.getCurrentPriority(), ctx.getContext(), t -> {
				ctx.setRequestBody(bodyProducer.getResult());
				requestBodyReady(ctx);
				return null;
			}), ctx.getRequestSent());
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
		OpenConnection newClient;
		try {
			newClient = openConnection(targetUri.getHost(), targetUri.getPort(), targetUri.getPath(),
				HTTPConstants.HTTPS_SCHEME.equalsIgnoreCase(targetUri.getScheme()), config, logger);
		} catch (Exception e) {
			ctx.getRequestSent().error(IO.error(e));
			return;
		}
		HTTP1ClientConnection newConn = new HTTP1ClientConnection(
			newClient.getClient(), newClient.getConnect(), newClient.isThroughProxy(), 1, config);
		ctx.setThroughProxy(newClient.isThroughProxy());
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
		r.headers = Task.cpu("Prepare HTTP Request headers", Priority.NORMAL, r.ctx.getContext(), (Task<ByteBuffer[], NoException> t) -> {
			HTTPClientRequest request = r.ctx.getRequest();
			ByteArrayStringIso8859Buffer headers = new ByteArrayStringIso8859Buffer();
			headers.setNewArrayStringCapacity(4096);
			StringBuilder pathPrefix = null;
			if (r.ctx.isThroughProxy() && !(tcp instanceof SSLClient)) {
				pathPrefix = new StringBuilder(128);
				pathPrefix.append(request.isSecure() ? HTTPConstants.HTTPS_SCHEME : HTTPConstants.HTTP_SCHEME);
				pathPrefix.append("://").append(request.getHostname()).append(':').append(request.getPort());
			}
			HTTP1RequestCommandProducer.generate(request, pathPrefix, headers);
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
				Task.cpu("Error sending request", Priority.RATHER_IMPORTANT, r.ctx.getContext(), t -> {
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
				Task.cpu("Send HTTP/1 request body", Priority.NORMAL, r.ctx.getContext(), t -> {
					if (!req.ctx.getRequest().isExpectingBody() ||
						(body.getValue1() != null && body.getValue1().longValue() == 0)) {
						req.ctx.getRequestSent().unblock();
						if (prev == null) {
							receiveResponse(req);
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
		Task.cpu("Signal HTTP/1 request handling", Priority.RATHER_IMPORTANT, r.ctx.getContext(), t -> {
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
		Task.cpu("Send HTTP Request headers", Priority.NORMAL, r.ctx.getContext(), t -> {
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
	
	public static HTTPClientResponse receiveResponse(
		TCPClient client, boolean isThroughProxy, HTTPClientRequest requestSent, HTTPClientConfiguration config
	) {
		HTTP1ClientConnection c = new HTTP1ClientConnection(client, new Async<>(true), isThroughProxy, 1, config);
		Request r = new Request();
		r.ctx = new HTTPClientRequestContext(c, requestSent);
		c.receiveResponse(r);
		return r.ctx.getResponse();
	}
	
	private void receiveResponse(Request r) {
		Task.cpu("Received HTTP/1 response", Priority.NORMAL, r.ctx.getContext(), task -> {
			PartialAsyncConsumer<ByteBuffer, IOException> statusLineConsumer = new HTTP1ResponseStatusConsumer(r.ctx.getResponse())
				.convert(ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), IO::error);
			r.receiveStatusLine = tcp.getReceiver().consume(statusLineConsumer, 4096, config.getTimeouts().getReceive());
			r.ctx.getResponse().setHeaders(new MimeHeaders());
			if (r.receiveStatusLine.isDone())
				receiveResponseHeaders(r);
			else
				r.receiveStatusLine.thenStart(Task.cpu("Receive HTTP response", Task.getCurrentPriority(), r.ctx.getContext(), t -> {
					receiveResponseHeaders(r);
					return null;
				}), true);
			return null;
		}).start();
	}
	
	private void receiveResponseHeaders(Request r) {
		if (r.receiveStatusLine.forwardIfNotSuccessful(r.ctx.getResponse().getHeadersReceived())) {
			if (logger.debug()) logger.debug("Receive status line error, stop connection", r.receiveStatusLine.getError());
			stop(r, true);
			return;
		}
		if (logger.debug()) logger.debug("Status line received: " + r.ctx.getResponse().getStatusCode()
			+ " (" + r.ctx.getResponse().getStatusMessage() + ") for " + r.ctx + " from " + tcp);
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
			receiveResponseBody(r);
		else
			r.receiveHeaders.thenStart(Task.cpu("Receive HTTP response", Task.getCurrentPriority(), r.ctx.getContext(), t -> {
				receiveResponseBody(r);
				return null;
			}), true);
	}

	private void receiveResponseBody(Request r) {
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
		Task.cpu("Redirect HTTP request", Priority.NORMAL, r.ctx.getContext(), t -> {
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
