package net.lecousin.framework.network.http1.client;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;

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
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
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
	public void reserve(HTTPClientRequestContext reservedFor) {
		Request r = new Request();
		r.ctx = reservedFor;
		synchronized (requests) {
			requests.add(r);
		}
	}

	@Override
	public AsyncSupplier<Boolean, NoException> send(HTTPClientRequestContext ctx) {
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
			if (previous != null && !previous.nextRequestCanBeSent)
				break;

			// if headers are not yet sent, send them
			if (r.headersSent == null) {
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
				if (!r.result.isDone())
					unblockResult(r, Boolean.FALSE);
				else {
					final Request req = r;
					Task.cpu("Error sending request", Priority.RATHER_IMPORTANT, t -> {
						req.headersSent.forwardIfNotSuccessful(req.ctx.getRequestSent());
						return null;
					}).start();
				}
				it.remove();
				continue; // cancel remaining pending requests
			}
			
			// if we didn't start to send body yet
			if (r.ctx.getRequestBody() != null) {
				if (!r.result.isDone()) unblockResult(r, Boolean.TRUE);
				Pair<Long, AsyncProducer<ByteBuffer, IOException>> body = r.ctx.getRequestBody();
				r.ctx.setRequestBody(null);
				// send body
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
			stop(r);
			return;
		}
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
			stop(r);
			return;
		}
		HTTPClientResponse response = r.ctx.getResponse();
		if (r.ctx.getOnHeadersReceived() != null) {
			Boolean close = r.ctx.getOnHeadersReceived().apply(response);
			if (close != null) {
				if (close.booleanValue())
					stop(r);
				return;
			}
		}
		
		if (handleRedirection(r))
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
				response.setEntity(new EmptyEntity(null, response.getHeaders()));
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
			r.ctx.getResponse().getBodyReceived().unblock();
			r.ctx.getResponse().getTrailersReceived().unblock();
			requestDone(r);
		});
		receiveBody.onErrorOrCancel(() -> {
			stop(r);
			receiveBody.forwardIfNotSuccessful(r.ctx.getResponse().getBodyReceived());
		});
		doNextJob();
	}
	
	private boolean handleRedirection(Request r) {
		HTTPClientResponse response = r.ctx.getResponse();
		if (r.ctx.getMaxRedirections() <= 0 || !HTTPResponse.isRedirectionStatusCode(response.getStatusCode()))
			return false;
		
		String location = response.getHeaders().getFirstRawValue(HTTPConstants.Headers.Response.LOCATION);
		if (location == null)
			return false;
		
		if (response.isConnectionClose()) {
			stopping = true;
			stop(r);
		} else {
			Long size = response.getHeaders().getContentLength();
			Async<IOException> skipBody;
			if (size != null && size.longValue() > 0)
				skipBody = tcp.getReceiver().skipBytes(size.intValue(), config.getTimeouts().getReceive());
			else
				skipBody = new Async<>(true);
			
			r.nextRequestCanBeSent = true;
			skipBody.onSuccess(() -> requestDone(r));
			skipBody.onErrorOrCancel(() -> stop(r));
		}
			
		try {
			r.ctx.redirectTo(location);
		} catch (URISyntaxException e) {
			IOException error = new IOException("Invalid redirect location: " + location, e);
			r.ctx.getResponse().getHeadersReceived().error(error);
		}
		return true;
	}

}
