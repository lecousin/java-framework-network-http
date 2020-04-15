package net.lecousin.framework.network.http2.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.http.server.HTTPServerResponse;
import net.lecousin.framework.network.http1.HTTP1RequestCommandProducer;
import net.lecousin.framework.network.http1.server.HTTP1ServerProtocol;
import net.lecousin.framework.network.http2.HTTP2Constants;
import net.lecousin.framework.network.http2.HTTP2PseudoHeaderHandler;
import net.lecousin.framework.network.http2.frame.HTTP2Headers;
import net.lecousin.framework.network.http2.streams.DataHandler;
import net.lecousin.framework.network.http2.streams.DataStreamHandler;
import net.lecousin.framework.network.http2.streams.StreamsManager;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.entity.EmptyEntity;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.util.Pair;

class ClientDataHandler implements DataHandler {

	private HTTPRequestContext ctx;
	private int streamId;
	private HTTP2ServerProtocol server;
	
	public ClientDataHandler(
		TCPServerClient client, int streamId,
		HTTP2ServerProtocol server
	) {
		this.ctx = new HTTPRequestContext(client, new HTTPRequest(), new HTTPServerResponse(), server.getErrorHandler());
		this.streamId = streamId;
		this.server = server;
	}
	
	@Override
	public void close() {
		if (!ctx.getResponse().getSent().isDone())
			ctx.getResponse().getSent().error(new ClosedChannelException());
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
	public void emptyEntityReceived(StreamsManager manager, DataStreamHandler stream) {
		ctx.getRequest().setEntity(new EmptyEntity(null, ctx.getRequest().getHeaders()));
	}
	
	@Override
	public AsyncConsumer<ByteBuffer, IOException> endOfHeaders(StreamsManager manager, DataStreamHandler stream) {
		// we can start processing the request
		if (manager.isClosing())
			return null;

		if (server.logger.debug())
			server.logger.debug("Processing request from " + ctx.getClient()
				+ ":\n" + HTTP1RequestCommandProducer.generateString(ctx.getRequest())
				+ "\n" + ctx.getRequest().getHeaders().generateString(1024).asString()
			);

		server.getProcessor().process(ctx);
		
		ctx.getResponse().getReady().onDone(() -> sendHeaders(manager, ctx));
		ctx.getResponse().getSent().onDone(() -> {
			manager.taskEndOfStream(streamId);
			if (server.logger.debug())
				server.logger.debug("Response sent to " + ctx.getClient()
					+ " for " + HTTP1RequestCommandProducer.generateString(ctx.getRequest()));
		});

		if (ctx.getRequest().getEntity() == null) {
			if (!ctx.getRequest().isExpectingBody())
				return null;
			server.logger.warn("Processor did not set an entity to receive the request body, default to binary");
			ctx.getRequest().setEntity(new BinaryEntity(null, ctx.getRequest().getHeaders()));
		}
		return ctx.getRequest().getEntity().createConsumer(ctx.getRequest().getHeaders().getContentLength());
	}
	
	@Override
	public void endOfBody(StreamsManager manager, DataStreamHandler stream) {
		// nothing
	}
	
	@Override
	public void endOfTrailers(StreamsManager manager, DataStreamHandler stream) {
		// nothing
	}
	
	@SuppressWarnings("java:S1602") // better readability to keep curly braces
	private void sendHeaders(StreamsManager manager, HTTPRequestContext ctx) {
		Task.cpu("Create HTTP/2 headers frame", (Task<Void, NoException> task) -> {
			if (ctx.getResponse().getStatusCode() < 100)
				ctx.getResponse().setStatus(ctx.getResponse().getReady().hasError() ? 500 : 200);
			
			if (((ClientStreamsManager)manager).getServer().rangeRequestsEnabled())
				HTTP1ServerProtocol.handleRangeRequest(ctx, manager.getLogger());
			
			AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> bodyProducer;
			if (ctx.getResponse().getEntity() == null) {
				bodyProducer = new AsyncSupplier<>(new Pair<>(Long.valueOf(0), null), null);
			} else {
				bodyProducer = ctx.getResponse().getEntity().createBodyProducer();
			}
			List<Pair<String, String>> headers = new LinkedList<>();
			headers.add(new Pair<>(HTTP2Constants.Headers.Response.Pseudo.STATUS, Integer.toString(ctx.getResponse().getStatusCode())));
			for (MimeHeader h : ctx.getResponse().getHeaders().getHeaders())
				headers.add(new Pair<>(h.getNameLowerCase(), h.getRawValue()));
			// if available, add the content-length
			boolean isEndOfStream = false;
			if (bodyProducer.isDone() && bodyProducer.getResult().getValue1() != null) {
				if (bodyProducer.getResult().getValue1().longValue() == 0)
					isEndOfStream = true;
				else
					headers.add(new Pair<>("content-length", bodyProducer.getResult().getValue1().toString()));
			}
			if (ctx.getResponse().getTrailerHeadersSuppliers() != null)
				isEndOfStream = false;
			if (server.logger.debug()) {
				StringBuilder s = new StringBuilder("Ready to send response to ").append(ctx.getClient());
				s.append(" [").append(HTTP1RequestCommandProducer.generateString(ctx.getRequest())).append(']');
				for (Pair<String, String> p : headers)
					s.append('\n').append(p.getValue1()).append(": ").append(p.getValue2());
				server.logger.debug(s.toString());
			}
			// send headers/continuation frames, then body, then trailers
			final boolean eos = isEndOfStream;
			manager.reserveCompressionContext(streamId).thenStart("Send HTTP/2 headers", Priority.NORMAL, compressionContext -> {
				manager.sendFrame(new HTTP2Headers.Writer(streamId, headers, eos, compressionContext, () -> {
					manager.releaseCompressionContext(streamId);
					if (eos) {
						ctx.getResponse().getSent().unblock();
						return;
					}
					bodyProducer.onDone(() -> DataStreamHandler.sendBody(
						manager, streamId, ctx.getResponse(), ctx.getResponse().getSent(),
						bodyProducer.getResult().getValue1(), bodyProducer.getResult().getValue2(),
						128 * 1024
					));
				}), false);
			}, false);
			return null;
		}).start();
	}
	
}
