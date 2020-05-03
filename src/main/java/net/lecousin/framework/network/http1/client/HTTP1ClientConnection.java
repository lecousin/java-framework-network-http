package net.lecousin.framework.network.http1.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.Executable;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.async.JoinPoint;
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
	private Request previousRequest = null;
	private int nbPendingRequests = 0;
	private long idleStart = -1;
	private LinkedList<Request> reserved = new LinkedList<>();
	
	private static class Request {
		private Request(HTTPClientRequestContext ctx) {
			this.ctx = ctx;
		}
		
		private HTTPClientRequestContext ctx;
		private AsyncSupplier<ByteBuffer[], IOException> headers = new AsyncSupplier<>();
		private AsyncSupplier<Boolean, NoException> result = new AsyncSupplier<>();
		private Async<IOException> nextCanBeAccepted = new Async<>();
		private Async<IOException> nextCanBeReceived = new Async<>();
		
	}
	
	@Override
	public void setConnection(TCPClient tcp, Async<IOException> connect, boolean isThroughProxy) {
		super.setConnection(tcp, connect, isThroughProxy);
		clientConsumer = tcp.asConsumer(5, config.getTimeouts().getSend());
		Runnable onStop = () -> stopping = true;
		connect.onErrorOrCancel(onStop);
		tcp.onclosed(onStop);
	}
	
	@Override
	public boolean hasPendingRequest() {
		return nbPendingRequests != 0;
	}

	@Override
	public boolean isAvailableForReuse() {
		return nbPendingRequests < maxPendingRequests;
	}
	
	@Override
	public long getIdleTime() {
		return idleStart;
	}
	
	@Override
	public String getDescription() {
		if (idleStart > 0)
			return "idle since " + ((System.currentTimeMillis() - idleStart) / 1000) + "s.";
		return nbPendingRequests + " pending requests";
	}

	@Override
	public void reserve(HTTPClientRequestContext reservedFor) {
		Request r = new Request(reservedFor);
		synchronized (reserved) {
			nbPendingRequests++;
			reserved.add(r);
			idleStart = -1;
		}
		// prepare body and headers to be sent
		prepareBodyAndHeaders(r);
	}

	@Override
	public AsyncSupplier<Boolean, NoException> sendReserved(HTTPClientRequestContext ctx) {
		Request r = null;
		Request previous;
		synchronized (reserved) {
			for (Iterator<Request> it = reserved.iterator(); it.hasNext(); ) {
				Request req = it.next();
				if (req.ctx == ctx) {
					r = req;
					it.remove();
					break;
				}
			}
			if (stopping || r == null)
				return new AsyncSupplier<>(Boolean.FALSE, null);
			previous = previousRequest;
			previousRequest = r;
		}
		// send headers
		Request req = r;
		JoinPoint.from(r.headers, previous == null ? connect : previous.ctx.getRequestSent())
		.onDone(() -> sendHeaders(req, previous), () -> req.result.unblockSuccess(Boolean.FALSE));
		// once done
		r.nextCanBeReceived.onDone(() -> {
			synchronized (reserved) {
				if (--nbPendingRequests > 0) {
					idleStart = -1;
					return;
				}
			}
			int idle = config.getTimeouts().getIdle();
			if (idle <= 0) {
				stopping = true;
				tcp.close();
			} else {
				idleStart = System.currentTimeMillis();
				Task.cpu("Check HTTP1ClientConnection idle", new CheckIdle()).executeAt(idleStart + idle).start();
			}
		});
		return r.result;
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
		reserve(ctx);
		sendReserved(ctx);
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

	private class CheckIdle implements Executable<Void, NoException> {
		@Override
		public Void execute(Task<Void, NoException> taskContext) {
			synchronized (reserved) {
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
	
	public static HTTPClientResponse receiveResponse(
		TCPClient client, boolean isThroughProxy, HTTPClientRequest requestSent, HTTPClientConfiguration config
	) {
		HTTP1ClientConnection c = new HTTP1ClientConnection(client, new Async<>(true), isThroughProxy, 1, config);
		Request r = new Request(new HTTPClientRequestContext(c, requestSent));
		c.receiveResponseStatus(r, null);
		return r.ctx.getResponse();
	}
	
	private static final String CLIENT_STOPPED = "HTTP1ClientConnection closed";
	
	private void prepareBodyAndHeaders(Request r) {
		Task<Void, NoException> prepareHeaders = Task.cpu("Prepare HTTP/1 Request headers", Priority.NORMAL, r.ctx.getContext(), t -> {
			if (stopping) {
				r.headers.cancel(new CancelException(CLIENT_STOPPED));
				r.nextCanBeAccepted.cancel(new CancelException(CLIENT_STOPPED));
				r.nextCanBeReceived.cancel(new CancelException(CLIENT_STOPPED));
				return null;
			}
			HTTPClientRequest request = r.ctx.getRequest();
			Pair<Long, AsyncProducer<ByteBuffer, IOException>> body = r.ctx.getRequestBody();
			if (body.getValue1() == null || request.getTrailerHeadersSuppliers() != null) {
				request.setHeader(MimeHeaders.TRANSFER_ENCODING, ChunkedTransfer.TRANSFER_NAME);
				request.getHeaders().remove(MimeHeaders.CONTENT_LENGTH);
			} else if (body.getValue1().longValue() > 0 || HTTPRequest.methodMayContainBody(request.getMethod())) {
				request.getHeaders().setContentLength(body.getValue1().longValue());
				request.getHeaders().remove(MimeHeaders.TRANSFER_ENCODING);
			} else {
				request.getHeaders().remove(MimeHeaders.TRANSFER_ENCODING);
				request.getHeaders().remove(MimeHeaders.CONTENT_LENGTH);
			}
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
			r.headers.unblockSuccess(headers.asByteBuffers());
			return null;
		});

		if (r.ctx.getRequestBody() == null) {
			AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> bodyProducer = r.ctx.prepareRequestBody();
			bodyProducer.onDone(() -> {
				r.ctx.setRequestBody(bodyProducer.getResult());
				prepareHeaders.start();
			}, () -> {
				bodyProducer.forwardIfNotSuccessful(r.ctx.getRequestSent());
				bodyProducer.forwardIfNotSuccessful(r.headers);
				bodyProducer.forwardIfNotSuccessful(r.nextCanBeAccepted);
				bodyProducer.forwardIfNotSuccessful(r.nextCanBeReceived);
			});
		} else {
			prepareHeaders.start();
		}
	}
	
	private void sendHeaders(Request r, Request previous) {
		Task.cpu("Send HTTP/1 Request headers", Priority.NORMAL, r.ctx.getContext(), t -> {
			if (logger.debug()) logger.debug("Sending HTTP/1 headers for " + r.ctx + " to " + tcp);
			// send headers
			IAsync<IOException> send = clientConsumer.push(Arrays.asList(r.headers.getResult()));
			if (previous == null) // first request => we accept it
				r.result.unblockSuccess(Boolean.TRUE);
			// look at the body
			Pair<Long, AsyncProducer<ByteBuffer, IOException>> body = r.ctx.getRequestBody();
			if (!r.ctx.getRequest().isExpectingBody() ||
				(body.getValue1() != null && body.getValue1().longValue() == 0)) {
				// no body
				if (previous == null) {
					// first request
					send.onDone(r.ctx.getRequestSent());
					receiveResponseStatus(r, previous);
					return null;
				}
				// we need to know if the next request can be accepted
				previous.nextCanBeAccepted.onDone(() -> {
					r.result.unblockSuccess(Boolean.TRUE);
					send.onDone(r.ctx.getRequestSent());
					receiveResponseStatus(r, previous);
				}, () -> r.result.unblockSuccess(Boolean.FALSE));
				return null;
			}
			
			// we have a body to send
			if (previous == null || r.ctx.getRequest().getEntity().canProduceBodyMultipleTimes()) {
				sendBody(r, previous, send);
				return null;
			}
			// we need to know if the next request can be accepted
			previous.nextCanBeAccepted.onDone(() -> {
				r.result.unblockSuccess(Boolean.TRUE);
				sendBody(r, previous, send);
			}, () -> r.result.unblockSuccess(Boolean.FALSE));
			return null;
		}).start();
	}

	private void sendBody(Request r, Request previous, IAsync<IOException> headersSent) {
		Task.cpu("Send HTTP/1 Request body", Priority.NORMAL, r.ctx.getContext(), t -> {
			if (logger.debug()) logger.debug("Sending HTTP/1 body for " + r.ctx + " to " + tcp);
			// send the body
			Pair<Long, AsyncProducer<ByteBuffer, IOException>> body = r.ctx.getRequestBody();
			AsyncConsumer<ByteBuffer, IOException> consumer = 
				body.getValue1() == null || r.ctx.getRequest().getTrailerHeadersSuppliers() != null
					? new ChunkedTransfer.Sender(clientConsumer, r.ctx.getRequest().getTrailerHeadersSuppliers())
					: clientConsumer;
			Async<IOException> sendBody = body.getValue2()
				.toConsumer(consumer, "Send HTTP request body", Task.getCurrentPriority());
			
			if (r.result.isDone()) {
				// request already accepted
				sendBody.onDone(r.ctx.getRequestSent());
				receiveResponseStatus(r, previous);
				return null;
			}
			
			// we need to know if the next request can be accepted
			previous.nextCanBeAccepted.onDone(() -> {
				// next request is accepted
				r.result.unblockSuccess(Boolean.TRUE);
				sendBody.onDone(r.ctx.getRequestSent());
				receiveResponseStatus(r, previous);
			}, () -> {
				// we cannot continue
				r.ctx.setRequestBody(null);
				r.result.unblockSuccess(Boolean.FALSE);
			});
			return null;
		}).startOn(headersSent, true);
	}
	
	private void receiveResponseStatus(Request r, Request previous) {
		Task<Void, NoException> task = Task.cpu("Receive HTTP/1 response status line", Priority.NORMAL, r.ctx.getContext(), t -> {
			if (!r.ctx.getRequestSent().isSuccessful()) {
				r.ctx.getRequestSent().forwardIfNotSuccessful(r.nextCanBeAccepted);
				r.ctx.getRequestSent().forwardIfNotSuccessful(r.nextCanBeReceived);
			}
			if (previous != null && previous.nextCanBeReceived.forwardIfNotSuccessful(r.ctx.getResponse().getHeadersReceived())) {
				previous.nextCanBeReceived.forwardIfNotSuccessful(r.nextCanBeAccepted);
				previous.nextCanBeReceived.forwardIfNotSuccessful(r.nextCanBeReceived);
				return null;
			}
			if (logger.debug()) logger.debug("Waiting for HTTP/1 status for " + r.ctx + " from " + tcp);
			PartialAsyncConsumer<ByteBuffer, IOException> statusLineConsumer = new HTTP1ResponseStatusConsumer(r.ctx.getResponse())
				.convert(ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), IO::error);
			IAsync<IOException> receive = tcp.getReceiver().consume(statusLineConsumer, 4096, config.getTimeouts().getReceive());
			r.ctx.getResponse().setHeaders(new MimeHeaders());
			receive.onDone(
				() -> receiveResponseHeaders(r),
				error -> {
					r.ctx.getResponse().getHeadersReceived().error(error);
					r.nextCanBeAccepted.error(error);
					r.nextCanBeReceived.error(error);
				},
				cancel -> {
					r.ctx.getResponse().getHeadersReceived().cancel(cancel);
					r.nextCanBeAccepted.cancel(cancel);
					r.nextCanBeReceived.cancel(cancel);
				}
			);
			return null;
		});
		if (previous == null)
			task.start();
		else
			task.startOn(previous.nextCanBeReceived, true);
	}

	private void receiveResponseHeaders(Request r) {
		Task.cpu("Receive HTTP/1 response headers", Priority.NORMAL, r.ctx.getContext(), t -> {
			if (logger.debug()) logger.debug("Status line received: " + r.ctx.getResponse().getStatusCode()
				+ " (" + r.ctx.getResponse().getStatusMessage() + ") for " + r.ctx + " from " + tcp);
			
			if (r.ctx.getOnStatusReceived() != null) {
				Boolean close = r.ctx.getOnStatusReceived().apply(r.ctx.getResponse());
				if (close != null) {
					r.nextCanBeAccepted.cancel(new CancelException(CLIENT_STOPPED));
					if (close.booleanValue())
						tcp.close();
					return null;
				}
			}
			PartialAsyncConsumer<ByteBuffer, IOException> headersConsumer = r.ctx.getResponse().getHeaders()
				.createConsumer(config.getLimits().getHeadersLength())
				.convert(ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), IO::error);
			IAsync<IOException> receive = tcp.getReceiver().consume(headersConsumer, 4096, config.getTimeouts().getReceive());
			receive.onDone(
				() -> receiveResponseBody(r),
				error -> {
					r.ctx.getResponse().getHeadersReceived().error(error);
					r.nextCanBeAccepted.error(error);
					r.nextCanBeReceived.error(error);
				},
				cancel -> {
					r.ctx.getResponse().getHeadersReceived().cancel(cancel);
					r.nextCanBeAccepted.cancel(cancel);
					r.nextCanBeReceived.cancel(cancel);
				}
			);
			return null;
		}).start();
	}

	private void receiveResponseBody(Request r) {
		Task.cpu("Receive HTTP/1 response body", Priority.NORMAL, r.ctx.getContext(), t -> {
			if (logger.debug()) logger.debug("Headers received for " + r.ctx + " from " + tcp);
			
			HTTPClientResponse response = r.ctx.getResponse();
			if (r.ctx.getOnHeadersReceived() != null) {
				Boolean close = r.ctx.getOnHeadersReceived().apply(response);
				if (close != null) {
					r.nextCanBeAccepted.cancel(new CancelException(CLIENT_STOPPED));
					if (close.booleanValue())
						tcp.close();
					return null;
				}
			}
			
			r.ctx.getResponse().getHeadersReceived().unblock();
			
			if (handleHeaders(r))
				return null;
			
			if (response.isConnectionClose()) {
				stopping = true;
				r.nextCanBeAccepted.cancel(new CancelException(CLIENT_STOPPED));
			} else {
				r.nextCanBeAccepted.unblock();
			}

			receiveResponseBody(r, response);
			return null;
		}).start();
	}
	
	private void receiveResponseBody(Request r, HTTPClientResponse response) {
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
				r.ctx.getResponse().getHeadersReceived().unblock();
				r.ctx.getResponse().getBodyReceived().unblock();
				r.ctx.getResponse().getTrailersReceived().unblock();
				r.nextCanBeReceived.unblock();
				return;
			}
		} catch (Exception e) {
			IOException error = IO.error(e);
			r.ctx.getResponse().getBodyReceived().error(error);
			r.nextCanBeReceived.error(error);
			tcp.close();
			return;
		}
		if (logger.debug()) logger.debug("Start receiving body for " + r.ctx + " from " + tcp);
		IAsync<IOException> receiveBody = tcp.getReceiver().consume(transfer, getBufferSize(size), config.getTimeouts().getReceive());
		receiveBody.onSuccess(() -> {
			if (logger.debug()) logger.debug("Body received, end of request " + r.ctx + " from " + tcp);
			r.ctx.getResponse().getBodyReceived().unblock();
			r.ctx.getResponse().getTrailersReceived().unblock();
			r.nextCanBeReceived.unblock();
		});
		receiveBody.onErrorOrCancel(() -> {
			receiveBody.forwardIfNotSuccessful(r.ctx.getResponse().getBodyReceived());
			receiveBody.forwardIfNotSuccessful(r.nextCanBeReceived);
			tcp.close();
		});
	}
	
	private static int getBufferSize(Long size) {
		if (size == null)
			return 8192;
		if (size.longValue() <= 1024)
			return 1024;
		if (size.longValue() <= 64 * 1024)
			return size.intValue();
		return 64 * 1024;
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
		
		if (logger.debug()) logger.debug("Redirection requested (" + r.ctx.getMaxRedirections() + " remaining)");
		Async<IOException> skipBody = null;
		if (response.isConnectionClose()) {
			r.nextCanBeAccepted.cancel(new CancelException(CLIENT_STOPPED));
			tcp.close();
		} else {
			Long size = response.getHeaders().getContentLength();
			if (size != null && size.longValue() > 0 && size.longValue() < 65536) {
				skipBody = tcp.getReceiver().skipBytes(size.intValue(), config.getTimeouts().getReceive());
				r.nextCanBeAccepted.unblock();
				if (logger.debug()) logger.debug("Skip body of redirection from " + r.ctx + " on " + tcp);
				skipBody.onSuccess(() -> r.nextCanBeReceived.unblock());
				skipBody.onErrorOrCancel(() -> {
					r.nextCanBeReceived.cancel(new CancelException(CLIENT_STOPPED));
					tcp.close();
				});
			} else if (size != null && size.longValue() == 0) {
				r.nextCanBeAccepted.unblock();
				r.nextCanBeReceived.unblock();
			} else {
				if (logger.debug()) logger.debug("Stop connection to avoid receiving body before redirection");
				r.nextCanBeAccepted.cancel(new CancelException(CLIENT_STOPPED));
				tcp.close();
			}
		}
		if (skipBody == null)
			skipBody = new Async<>(true);
		
		synchronized (reserved) {
			if (previousRequest == r)
				previousRequest = null;
		}
		
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
