package net.lecousin.framework.network.http2.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedList;

import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http.client.HTTPClientResponse;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.HTTP2PseudoHeaderHandler;
import net.lecousin.framework.network.http2.streams.DataHandler;
import net.lecousin.framework.network.http2.streams.DataStreamHandler;
import net.lecousin.framework.network.http2.streams.StreamsManager;
import net.lecousin.framework.network.mime.entity.DefaultMimeEntityFactory;
import net.lecousin.framework.network.mime.entity.EmptyEntity;
import net.lecousin.framework.network.mime.entity.MimeEntity;
import net.lecousin.framework.network.mime.entity.MimeEntityFactory;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.transfer.ContentDecoderFactory;
import net.lecousin.framework.network.mime.transfer.TransferEncodingFactory;

class ClientRequestDataHandler implements DataHandler {
	
	public ClientRequestDataHandler(HTTPClientRequestContext ctx) {
		this.ctx = ctx;
	}
	
	private HTTPClientRequestContext ctx;
	
	@Override
	public void close() {
		if (!ctx.getRequestSent().isDone())
			ctx.getRequestSent().error(new ClosedChannelException());
		else if (!ctx.getResponse().getHeadersReceived().isDone())
			ctx.getResponse().getHeadersReceived().error(new ClosedChannelException());
		else if (!ctx.getResponse().getBodyReceived().isDone())
			ctx.getResponse().getBodyReceived().error(new ClosedChannelException());
		else if (!ctx.getResponse().getTrailersReceived().isDone())
			ctx.getResponse().getTrailersReceived().error(new ClosedChannelException());
	}
	
	@Override
	public void error(int errorCode) {
		IOException error = new IOException("Server returned HTTP/2 error " + errorCode);
		if (!ctx.getRequestSent().isDone())
			ctx.getRequestSent().error(error);
		else if (!ctx.getResponse().getHeadersReceived().isDone())
			ctx.getResponse().getHeadersReceived().error(error);
		else if (!ctx.getResponse().getBodyReceived().isDone())
			ctx.getResponse().getBodyReceived().error(error);
		else if (!ctx.getResponse().getTrailersReceived().isDone())
			ctx.getResponse().getTrailersReceived().error(error);
	}

	@Override
	public MimeHeaders getReceivedHeaders() {
		return ctx.getResponse().getHeaders();
	}

	@Override
	public HTTP2PseudoHeaderHandler createPseudoHeaderHandler() {
		return new HTTP2PseudoHeaderHandler.Response(ctx.getResponse());
	}

	@Override
	public void emptyEntityReceived(StreamsManager manager, DataStreamHandler stream) {
		ctx.getResponse().setEntity(new EmptyEntity(null, ctx.getResponse().getHeaders()));
	}

	@Override
	public AsyncConsumer<ByteBuffer, IOException> endOfHeaders(StreamsManager manager, DataStreamHandler stream) throws Exception {
		if (manager.getLogger().debug())
			manager.getLogger().debug("End of headers on stream " + stream.getStreamId() + ":\n"
				+ ctx.getResponse().getStatusCode() + " " + ctx.getResponse().getStatusMessage() + "\n"
				+ ctx.getResponse().getHeaders().generateString(1024).asString());
		if (handleHeaders(manager, stream))
			return null;

		ctx.getResponse().getHeadersReceived().unblock();
		
		// set entity
		MimeEntity entity = ctx.getResponse().getEntity();
		Long length = ctx.getResponse().getHeaders().getContentLength();
		if (entity == null) {
			if (length != null && length.longValue() == 0) {
				entity = new EmptyEntity(null, ctx.getResponse().getHeaders());
			} else {
				MimeEntityFactory factory = ctx.getEntityFactory();
				if (factory == null) factory = DefaultMimeEntityFactory.getInstance();
				entity = factory.create(null, ctx.getResponse().getHeaders());
			}
			ctx.getResponse().setEntity(entity);
		}
		if ((entity instanceof EmptyEntity) || (length != null && length.longValue() == 0)) {
			if (manager.getLogger().debug())
				manager.getLogger().debug("Empty response on stream " + stream.getStreamId());
			ctx.getResponse().getBodyReceived().unblock();
			ctx.getResponse().getTrailersReceived().unblock();
			return null;
		}
		
		AsyncConsumer<ByteBuffer, IOException> consumer = ctx.getResponse().getEntity().createConsumer(length);
		LinkedList<String> encoding = new LinkedList<>();
		TransferEncodingFactory.addEncodingFromHeader(ctx.getResponse().getHeaders(), MimeHeaders.CONTENT_ENCODING, encoding);
		for (String coding : encoding)
			consumer = ContentDecoderFactory.createDecoder(consumer, coding);
		return consumer;
	}
	
	@Override
	public void endOfBody(StreamsManager manager, DataStreamHandler stream) {
		if (manager.getLogger().debug())
			manager.getLogger().debug("End of body on stream " + stream.getStreamId());
		ctx.getResponse().getBodyReceived().unblock();
	}
	
	@Override
	public void endOfTrailers(StreamsManager manager, DataStreamHandler stream) {
		if (manager.getLogger().debug())
			manager.getLogger().debug("End of trailers on stream " + stream.getStreamId());
		ctx.getResponse().getTrailersReceived().unblock();
	}

	
	private boolean handleHeaders(StreamsManager manager, DataStreamHandler stream) {
		try {
			HTTPClient.addKnowledgeFromResponseHeaders(ctx.getRequest(), ctx.getResponse(),
				(InetSocketAddress)((ClientStreamsManager)manager).getRemote().getRemoteAddress(),
				ctx.isThroughProxy());
		} catch (Exception e) {
			manager.getLogger().error("Unexpected error", e);
		}
		
		return handleRedirection(manager, stream);
	}
	
	private boolean handleRedirection(StreamsManager manager, DataStreamHandler stream) {
		HTTPClientResponse response = ctx.getResponse();
		if (!HTTPResponse.isRedirectionStatusCode(response.getStatusCode()))
			return false;
		if (ctx.getMaxRedirections() <= 0) {
			if (manager.getLogger().debug()) manager.getLogger().debug("No more redirection allowed, handle the response");
			return false;
		}
		
		String location = response.getHeaders().getFirstRawValue(HTTPConstants.Headers.Response.LOCATION);
		if (location == null) {
			if (manager.getLogger().warn()) manager.getLogger().warn("No location given for redirection");
			return false;
		}

		if (manager.getLogger().debug()) manager.getLogger().debug("Redirect to " + location);

		HTTPClientRequestContext c = ctx;
		ctx = null;
		stream.resetStream(manager, HTTP2Error.Codes.NO_ERROR);
		
		Task.cpu("Redirect HTTP request", Priority.NORMAL, ctx.getContext(), t -> {
			try {
				c.redirectTo(location);
			} catch (URISyntaxException e) {
				IOException error = new IOException("Invalid redirect location: " + location, e);
				c.getResponse().getHeadersReceived().error(error);
			}
			return null;
		}).start();
		return true;
	}

}
