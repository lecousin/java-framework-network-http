package net.lecousin.framework.network.http1.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;

import net.lecousin.framework.application.LCCore;
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
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientConnection;
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
	public HTTP1ClientConnection(TCPClient client, IAsync<IOException> connect, int maxPendingRequests, HTTPClientConfiguration config) {
		super(client, connect);
		this.maxPendingRequests = maxPendingRequests;
		this.config = config;
		this.logger = LCCore.getApplication().getLoggerFactory().getLogger(HTTP1ClientConnection.class);
		connect.onDone(() -> {
			if (connect.isSuccessful())
				 clientConsumer = client.asConsumer(3, config.getTimeouts().getSend());
			else
				stopping = true;
			doNextJob();
		});
		client.onclosed(() -> {
			stopping = true;
			doNextJob();
		});
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
				else if (!r.receiveStatusLine.isDone())
					s.append("waiting for response status line");
				else if (!r.receiveHeaders.isDone())
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
				if (logger.debug()) logger.debug("Send headers for request " + r.ctx);
				sendHeaders(r);
				unblockResult(r, Boolean.TRUE);
				break;
			}
			
			// if we are still sending headers, wait
			if (!r.headersSent.isDone())
				break;
			
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
				if (logger.debug()) logger.debug("Send body for request " + r.ctx);
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
						stop(req);
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
			r.headersSent.onDone(HTTP1ClientConnection.this::doNextJob);
			return null;
		}).start();
	}
	
	private void stop(Request r) {
		stopping = true;
		synchronized (requests) {
			requests.remove(r);
		}
		tcp.close();
		doNextJob();
	}
	
	private void requestDone(Request r) {
		synchronized (requests) {
			requests.remove(r);
		}
		doNextJob();
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
			stop(r);
			return;
		}
		if (logger.debug()) logger.debug("Status line received: " + r.ctx.getResponse().getStatusCode());
		if (r.ctx.getOnStatusReceived() != null) {
			Boolean close = r.ctx.getOnStatusReceived().apply(r.ctx.getResponse());
			if (close != null) {
				if (close.booleanValue())
					stop(r);
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
			stop(r);
			return;
		}
		if (logger.debug()) logger.debug("Headers received");
		HTTPClientResponse response = r.ctx.getResponse();
		if (r.ctx.getOnHeadersReceived() != null) {
			Boolean close = r.ctx.getOnHeadersReceived().apply(response);
			if (close != null) {
				if (close.booleanValue())
					stop(r);
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
			stop(r);
			r.ctx.getResponse().getBodyReceived().error(IO.error(e));
			return;
		}
		if (logger.debug()) logger.debug("Start receiving body");
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
			if (logger.debug()) logger.debug("Body received, end of request");
			requestDone(r);
			r.ctx.getResponse().getBodyReceived().unblock();
			r.ctx.getResponse().getTrailersReceived().unblock();
		});
		receiveBody.onErrorOrCancel(() -> {
			stop(r);
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
		
		if (response.isConnectionClose()) {
			stop(r);
		} else {
			Long size = response.getHeaders().getContentLength();
			if (size != null && size.longValue() > 0 && size.longValue() < 65536) {
				Async<IOException> skipBody = tcp.getReceiver().skipBytes(size.intValue(), config.getTimeouts().getReceive());
				r.nextRequestCanBeSent = true;
				if (logger.debug()) logger.debug("Skip body of redirection");
				skipBody.onSuccess(() -> requestDone(r));
				skipBody.onErrorOrCancel(() -> stop(r));
			} else if (size != null && size.longValue() == 0) {
				r.nextRequestCanBeSent = true;
				requestDone(r);
			} else {
				if (logger.debug()) logger.debug("Stop connection to avoid receiving body before redirection");
				stop(r);
			}
		}
		
		if (logger.debug()) logger.debug("Redirect to " + location);
		Task.cpu("Redirect HTTP request", Priority.NORMAL, t -> {
			try {
				r.ctx.redirectTo(location);
			} catch (URISyntaxException e) {
				IOException error = new IOException("Invalid redirect location: " + location, e);
				r.ctx.getResponse().getHeadersReceived().error(error);
			}
			return null;
		}).start();
		return true;
	}

}
