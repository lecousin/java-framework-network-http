package net.lecousin.framework.network.http2.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.mutable.Mutable;
import net.lecousin.framework.mutable.MutableInteger;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.http.server.HTTPServerResponse;
import net.lecousin.framework.network.http1.HTTP1RequestCommandProducer;
import net.lecousin.framework.network.http2.HTTP2Constants;
import net.lecousin.framework.network.http2.HTTP2PseudoHeaderHandler;
import net.lecousin.framework.network.http2.frame.HTTP2Data;
import net.lecousin.framework.network.http2.frame.HTTP2Headers;
import net.lecousin.framework.network.http2.streams.DataHandler;
import net.lecousin.framework.network.mime.entity.EmptyEntity;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.util.Pair;

class ClientDataHandler implements DataHandler {

	private ClientStreamsManager manager;
	private HTTPRequestContext ctx;
	private int streamId;
	private HTTP2ServerProtocol server;
	
	public ClientDataHandler(
		ClientStreamsManager manager, TCPServerClient client, int streamId,
		HTTP2ServerProtocol server
	) {
		this.manager = manager;
		this.ctx = new HTTPRequestContext(client, new HTTPRequest(), new HTTPServerResponse(), server.getErrorHandler());
		this.streamId = streamId;
		this.server = server;
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
	public void emptyEntityReceived() {
		ctx.getRequest().setEntity(new EmptyEntity(null, ctx.getRequest().getHeaders()));
	}
	
	@Override
	public AsyncConsumer<ByteBuffer, IOException> endOfHeaders() {
		// we can start processing the request
		if (manager.isClosing())
			return null;
		
		if (ctx.getRequest().getEntity() == null) {
			// body will come
			if (!ctx.getRequest().isExpectingBody()) {
				// TODO error ?
			}
		}
		// TODO manage priority and number of pending requests (both for the client and for the server?)
		if (server.logger.debug())
			server.logger.debug("Processing request from " + ctx.getClient()
				+ ": " + HTTP1RequestCommandProducer.generateString(ctx.getRequest()));
		server.getProcessor().process(ctx);
		
		ctx.getResponse().getReady().onDone(() -> sendHeaders(ctx));
		ctx.getResponse().getSent().thenStart("Close HTTP/2 stream", Priority.NORMAL, () -> manager.endOfStream(streamId), true);

		return ctx.getRequest().getEntity().createConsumer(ctx.getRequest().getHeaders().getContentLength());
	}
	
	@Override
	public void endOfBody() {
		// TODO Auto-generated method stub
		
	}
	
	@Override
	public void endOfTrailers() {
		// TODO Auto-generated method stub
		
	}
	
	private void sendHeaders(HTTPRequestContext ctx) {
		Task.cpu("Create HTTP/2 headers frame", (Task<Void, NoException> task) -> {
			// TODO if getReady() has an error, send an error
			// TODO handle range request
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
			// send headers/continuation frames, then body, then trailers
			final boolean eos = isEndOfStream;
			manager.reserveCompressionContext(streamId).thenStart("Send HTTP/2 headers", Priority.NORMAL, compressionContext -> {
				manager.sendFrame(new HTTP2Headers.Writer(streamId, headers, eos, compressionContext, () -> {
					manager.releaseCompressionContext(streamId);
					bodyProducer.onDone(
						() -> sendBody(ctx, bodyProducer.getResult().getValue1(), bodyProducer.getResult().getValue2())
					);
				}), false);
			}, false);
			return null;
		}).start();
	}
	
	private void sendBody(HTTPRequestContext ctx, Long bodySize, AsyncProducer<ByteBuffer, IOException> body) {
		if (bodySize != null && bodySize.longValue() == 0) {
			sendTrailers(ctx);
			return;
		}
		produceBody(ctx, body, new Mutable<>(null), new MutableInteger(0));
	}
	
	private static final int MAX_BODY_SIZE_PRODUCED = 128 * 1024;
	
	private void produceBody(
		HTTPRequestContext ctx,
		AsyncProducer<ByteBuffer, IOException> body, Mutable<HTTP2Data.Writer> lastWriter,
		MutableInteger sizeProduced
	) {
		body.produce("Produce HTTP/2 body data frame", Task.getCurrentPriority())
		.onDone(data -> {
			// TODO in a task
			if (data == null) {
				// end of data
				if (ctx.getResponse().getTrailerHeadersSuppliers() == null) {
					// end of stream
					if (lastWriter.get() != null && lastWriter.get().setEndOfStream()) {
						ctx.getResponse().getSent().unblock();
						return;
					}
					HTTP2Data.Writer w = new HTTP2Data.Writer(streamId, null, true,
						sent -> ctx.getResponse().getSent().unblock());
					manager.sendFrame(w, false);
					return;
				}
				// trailers will come
				sendTrailers(ctx);
				return;
			}
			synchronized (sizeProduced) {
				sizeProduced.add(data.remaining());
			}
			if (lastWriter.get() == null || !lastWriter.get().addData(data)) {
				lastWriter.set(new HTTP2Data.Writer(streamId, data, false, consumed -> {
					boolean canProduce;
					synchronized (sizeProduced) {
						canProduce = sizeProduced.get() >= MAX_BODY_SIZE_PRODUCED &&
							sizeProduced.get() - consumed.getValue2().intValue() < MAX_BODY_SIZE_PRODUCED;
						sizeProduced.sub(consumed.getValue2().intValue());
					}
					if (canProduce)
						produceBody(ctx, body, lastWriter, sizeProduced);
				}));
				manager.sendFrame(lastWriter.get(), false);
			}
			synchronized (sizeProduced) {
				if (sizeProduced.get() < MAX_BODY_SIZE_PRODUCED)
					produceBody(ctx, body, lastWriter, sizeProduced);
			}
		}, error -> {
			// TODO
		}, cancel -> {
			// TODO
		});
	}
	
	private void sendTrailers(HTTPRequestContext ctx) {
		if (ctx.getResponse().getTrailerHeadersSuppliers() == null) {
			ctx.getResponse().getSent().unblock();
			return;
		}
		List<MimeHeader> headers = ctx.getResponse().getTrailerHeadersSuppliers().get();
		if (headers.isEmpty()) {
			// finally not ! we need to send end of stream
			HTTP2Data.Writer w = new HTTP2Data.Writer(streamId, null, true,
				sent -> ctx.getResponse().getSent().unblock());
			manager.sendFrame(w, false);
			return;
		}
		List<Pair<String, String>> list = new LinkedList<>();
		for (MimeHeader h : headers)
			list.add(new Pair<>(h.getNameLowerCase(), h.getRawValue()));
		manager.reserveCompressionContext(streamId).thenStart("Send HTTP/2 trailers", Priority.NORMAL, compressionContext -> {
			manager.sendFrame(new HTTP2Headers.Writer(streamId, list, true, compressionContext, () -> {
				manager.releaseCompressionContext(streamId);
				ctx.getResponse().getSent().unblock();
			}), false);
		}, false);
	}

}
