package net.lecousin.framework.network.http2.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.LibraryVersion;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.http.server.HTTPServerResponse;
import net.lecousin.framework.network.http1.HTTP1RequestCommandProducer;
import net.lecousin.framework.network.http1.server.HTTP1ServerProtocol;
import net.lecousin.framework.network.http2.HTTP2Constants;
import net.lecousin.framework.network.http2.HTTP2PseudoHeaderHandler;
import net.lecousin.framework.network.http2.connection.HTTP2Stream;
import net.lecousin.framework.network.mime.MimeException;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.entity.EmptyEntity;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.transfer.ContentDecoderFactory;
import net.lecousin.framework.network.mime.transfer.TransferEncodingFactory;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.util.Pair;

class ClientRequestStream extends HTTP2Stream {

	private HTTPRequestContext ctx;
	private HTTP2ServerProtocol server;
	
	public ClientRequestStream(HTTP2ServerProtocol.Connection conn, int streamId, HTTP2ServerProtocol server) {
		super(conn, streamId);
		this.ctx = new HTTPRequestContext((TCPServerClient)conn.getRemote(),
			new HTTPRequest(), new HTTPServerResponse(), server.getErrorHandler());
		this.server = server;
		ctx.getRequest().setAttribute(HTTPRequestContext.REQUEST_ATTRIBUTE_NANOTIME_START, Long.valueOf(System.nanoTime()));
	}
	
	@Override
	public void closed() {
		if (!ctx.getResponse().getSent().isDone())
			ctx.getResponse().getSent().error(new ClosedChannelException());
	}
	
	@Override
	public void errorReceived(int errorCode) {
		if (!ctx.getResponse().getSent().isDone())
			ctx.getResponse().getSent().error(new IOException("Client returned HTTP/2 error " + errorCode));
	}

	@Override
	public MimeHeaders getReceivedHeaders() {
		return ctx.getRequest().getHeaders();
	}
	
	@Override
	public HTTP2PseudoHeaderHandler createPseudoHeaderHandler() {
		return new HTTP2PseudoHeaderHandler.Request(ctx.getRequest());
	}
	
	@Override
	protected void endOfHeadersWithoutBody() {
		ctx.getRequest().setEntity(new EmptyEntity(null, ctx.getRequest().getHeaders()));
		ctx.getRequest().setAttribute(HTTPRequestContext.REQUEST_ATTRIBUTE_NANOTIME_BODY_RECEIVED, Long.valueOf(System.nanoTime()));
		processRequest();
	}
	
	@Override
	protected AsyncConsumer<ByteBuffer, IOException> endOfHeadersWithBody() {
		ctx.getRequest().setAttribute(HTTPRequestContext.REQUEST_ATTRIBUTE_NANOTIME_HEADERS_RECEIVED, Long.valueOf(System.nanoTime()));
		if (conn.isClosing())
			return null;
		processRequest();
		AsyncConsumer<ByteBuffer, IOException> consumer =
				ctx.getRequest().getEntity().createConsumer(ctx.getRequest().getHeaders().getContentLength());
		LinkedList<String> encoding = new LinkedList<>();
		try {
			TransferEncodingFactory.addEncodingFromHeader(ctx.getResponse().getHeaders(), MimeHeaders.CONTENT_ENCODING, encoding);
		} catch (MimeException e) {
			return new AsyncConsumer.Error<>(IO.error(e));
		}
		for (String coding : encoding)
			consumer = ContentDecoderFactory.createDecoder(consumer, coding);
		return consumer;
	}
	
	private void processRequest() {
		if (server.logger.debug())
			server.logger.debug("Processing request from " + ctx.getClient()
				+ ":\n" + HTTP1RequestCommandProducer.generateString(ctx.getRequest())
				+ "\n" + ctx.getRequest().getHeaders().generateString(1024).asString()
			);

		ctx.getRequest().setAttribute(HTTPRequestContext.REQUEST_ATTRIBUTE_NANOTIME_PROCESSING_START, Long.valueOf(System.nanoTime()));
		server.getProcessor().process(ctx); // TODO start a task with a new context
		
		ctx.getResponse().getReady().onDone(this::sendHeaders);
		ctx.getResponse().getSent().onDone(() -> {
			if (server.logger.debug())
				server.logger.debug("Response sent to " + ctx.getClient()
					+ " for " + HTTP1RequestCommandProducer.generateString(ctx.getRequest()));
		});

		if (ctx.getRequest().getEntity() == null) {
			server.logger.warn("Processor did not set an entity to receive the request body, default to binary");
			ctx.getRequest().setEntity(new BinaryEntity(null, ctx.getRequest().getHeaders()));
		}
	}
	
	@Override
	public void endOfBody() {
		ctx.getRequest().setAttribute(HTTPRequestContext.REQUEST_ATTRIBUTE_NANOTIME_BODY_RECEIVED, Long.valueOf(System.nanoTime()));
	}
	
	@Override
	public void endOfTrailers() {
		// nothing
	}
	
	private void sendHeaders() {
		ctx.getRequest().setAttribute(HTTPRequestContext.REQUEST_ATTRIBUTE_NANOTIME_RESPONSE_READY, Long.valueOf(System.nanoTime()));
		Task.cpu("Create HTTP/2 headers frame", (Task<Void, NoException> task) -> {
			if (ctx.getResponse().getStatusCode() < 100)
				ctx.getResponse().setStatus(ctx.getResponse().getReady().hasError() ? 500 : 200);
			
			if (server.rangeRequestsEnabled())
				HTTP1ServerProtocol.handleRangeRequest(ctx, server.logger);
			
			AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> bodyProducer;
			if (ctx.getResponse().getEntity() == null) {
				bodyProducer = new AsyncSupplier<>(new Pair<>(Long.valueOf(0), null), null);
			} else {
				bodyProducer = ctx.getResponse().getEntity().createBodyProducer();
			}
			List<Pair<String, String>> headers = new LinkedList<>();
			headers.add(new Pair<>(HTTP2Constants.Headers.Response.Pseudo.STATUS, Integer.toString(ctx.getResponse().getStatusCode())));
			if (!ctx.getResponse().getHeaders().has(HTTPConstants.Headers.Response.SERVER))
				ctx.getResponse().getHeaders().setRawValue(HTTPConstants.Headers.Response.SERVER,
					"net.lecousin.framework.network.http2/" + LibraryVersion.VERSION);
			for (MimeHeader h : ctx.getResponse().getHeaders().getHeaders())
				headers.add(new Pair<>(h.getNameLowerCase(), h.getRawValue()));
			
			bodyProducer.thenDoOrStart("Send HTTP/2 response", task.getPriority(), () -> {
				Long bodySize = bodyProducer.getResult().getValue1();

				// if available, add the content-length
				AsyncProducer<ByteBuffer, IOException> body = bodyProducer.getResult().getValue2();
				if (bodySize != null && bodySize.longValue() != 0)
					headers.add(new Pair<>("content-length", bodyProducer.getResult().getValue1().toString()));

				if (server.logger.debug()) {
					StringBuilder s = new StringBuilder("Ready to send response to ").append(ctx.getClient());
					s.append(" [").append(HTTP1RequestCommandProducer.generateString(ctx.getRequest())).append(']');
					for (Pair<String, String> p : headers)
						s.append('\n').append(p.getValue1()).append(": ").append(p.getValue2());
					server.logger.debug(s.toString());
				}

				// send headers/continuation frames, then body, then trailers
				send(headers, ctx.getResponse(), ctx.getResponse().getSent(), bodySize, body, 128 * 1024);
			});
			return null;
		}).start();
	}
	
}
