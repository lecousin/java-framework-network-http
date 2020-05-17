package net.lecousin.framework.network.http2.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http.client.HTTPClientResponse;
import net.lecousin.framework.network.http2.HTTP2Constants;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.HTTP2PseudoHeaderHandler;
import net.lecousin.framework.network.http2.connection.HTTP2Connection;
import net.lecousin.framework.network.http2.connection.HTTP2Stream;
import net.lecousin.framework.network.mime.entity.DefaultMimeEntityFactory;
import net.lecousin.framework.network.mime.entity.EmptyEntity;
import net.lecousin.framework.network.mime.entity.MimeEntity;
import net.lecousin.framework.network.mime.entity.MimeEntityFactory;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.transfer.ContentDecoderFactory;
import net.lecousin.framework.network.mime.transfer.TransferEncodingFactory;
import net.lecousin.framework.text.ByteArrayStringIso8859Buffer;
import net.lecousin.framework.util.Pair;

public class ClientRequestStream extends HTTP2Stream {
	
	public ClientRequestStream(HTTP2Connection conn, HTTPClientRequestContext ctx) {
		super(conn, -1);
		this.ctx = ctx;
	}
	
	private HTTPClientRequestContext ctx;
	
	@Override
	protected void closed() {
		if (ctx == null)
			return; // redirected
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
	protected void errorReceived(int errorCode) {
		if (ctx == null)
			return; // redirected
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
	
	void send() {
		Task.cpu("Create HTTP/2 headers frame", null, ctx.getContext(), (Task<Void, NoException> task) -> {
			if (((TCPClient)conn.getRemote()).isClosed() || conn.isClosing()) {
				ctx.getRequestSent().error(new ClosedChannelException());
				return null;
			}
			if (ctx.getRequestBody() != null) {
				sendRequestBodyReady(ctx);
			} else {
				AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> bodyProducer =
					ctx.prepareRequestBody();
				if (bodyProducer.isSuccessful()) {
					ctx.setRequestBody(bodyProducer.getResult());
					sendRequestBodyReady(ctx);
				} else {
					bodyProducer.thenStart("Create HTTP/2 headers frame", Task.getCurrentPriority(), body -> {
						ctx.setRequestBody(body);
						sendRequestBodyReady(ctx);
					}, ctx.getRequestSent());
				}
			}
			return null;
		}).start();
	}

	private void sendRequestBodyReady(HTTPClientRequestContext ctx) {
		List<Pair<String, String>> headers = new LinkedList<>();
		headers.add(new Pair<>(HTTP2Constants.Headers.Request.Pseudo.SCHEME, ctx.getRequest().isSecure() ? "https" : "http"));
		headers.add(new Pair<>(HTTP2Constants.Headers.Request.Pseudo.METHOD, ctx.getRequest().getMethod()));
		headers.add(new Pair<>(HTTP2Constants.Headers.Request.Pseudo.AUTHORITY,
			ctx.getRequest().getHostname() + ":" + ctx.getRequest().getPort()));
		ByteArrayStringIso8859Buffer fullPath = new ByteArrayStringIso8859Buffer();
		ByteArrayStringIso8859Buffer path = ctx.getRequest().getEncodedPath();
		if (path == null)
			fullPath.append('/');
		else
			fullPath.append(path);
		ByteArrayStringIso8859Buffer query = ctx.getRequest().getEncodedQueryString();
		if (!query.isEmpty()) {
			fullPath.append('?');
			fullPath.append(query);
		}
		headers.add(new Pair<>(HTTP2Constants.Headers.Request.Pseudo.PATH, fullPath.asString()));
		for (MimeHeader h : ctx.getRequest().getHeaders().getHeaders())
			headers.add(new Pair<>(h.getNameLowerCase(), h.getRawValue()));
		// if available, add the content-length
		Long size = ctx.getRequestBody().getValue1();
		if (size != null && size.longValue() != 0)
			headers.add(new Pair<>("content-length", size.toString()));
		if (conn.getLogger().debug())
			conn.getLogger().debug("HTTP/2 headers to send:\n" + headers);
		// send headers/continuation frames, then body, then trailers
		send(headers, ctx.getRequest(), ctx.getRequestSent(), size, ctx.getRequestBody().getValue2(), 128 * 1024);
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
	protected void endOfHeadersWithoutBody() {
		if (handleHeaders())
			return;
		if (conn.getLogger().debug()) conn.getLogger().debug("Empty response on stream " + getStreamId());
		ctx.getResponse().setEntity(new EmptyEntity(null, ctx.getResponse().getHeaders()));
		ctx.getResponse().getHeadersReceived().unblock();
		ctx.getResponse().getBodyReceived().unblock();
		ctx.getResponse().getTrailersReceived().unblock();
	}
	
	@Override
	protected AsyncConsumer<ByteBuffer, IOException> endOfHeadersWithBody() throws Exception {
		if (handleHeaders())
			return new AsyncConsumer.Simple<ByteBuffer, IOException>() {
				@Override
				public IAsync<IOException> consume(ByteBuffer data) {
					data.position(data.limit());
					return new Async<>(true);
				}
			};

		ctx.getResponse().getHeadersReceived().unblock();
		
		// set entity
		MimeEntity entity = ctx.getResponse().getEntity();
		Long length = ctx.getResponse().getHeaders().getContentLength();
		if (entity == null) {
			MimeEntityFactory factory = ctx.getEntityFactory();
			if (factory == null) factory = DefaultMimeEntityFactory.getInstance();
			entity = factory.create(null, ctx.getResponse().getHeaders());
			ctx.getResponse().setEntity(entity);
		}
		
		AsyncConsumer<ByteBuffer, IOException> consumer = ctx.getResponse().getEntity().createConsumer(length);
		LinkedList<String> encoding = new LinkedList<>();
		TransferEncodingFactory.addEncodingFromHeader(ctx.getResponse().getHeaders(), MimeHeaders.CONTENT_ENCODING, encoding);
		for (String coding : encoding)
			consumer = ContentDecoderFactory.createDecoder(consumer, coding);
		return consumer;
	}
	
	@Override
	protected void endOfBody() {
		if (conn.getLogger().debug()) conn.getLogger().debug("End of body on stream " + getStreamId());
		ctx.getResponse().getBodyReceived().unblock();
	}
	
	@Override
	protected void endOfTrailers() {
		if (conn.getLogger().debug()) conn.getLogger().debug("End of trailers on stream " + getStreamId());
		ctx.getResponse().getTrailersReceived().unblock();
	}

	
	private boolean handleHeaders() {
		if (conn.getLogger().debug())
			conn.getLogger().debug("End of headers on stream " + getStreamId() + ":\n"
				+ ctx.getResponse().getStatusCode() + " " + ctx.getResponse().getStatusMessage() + "\n"
				+ ctx.getResponse().getHeaders().generateString(1024).asString());
		try {
			HTTPClient.addKnowledgeFromResponseHeaders(ctx.getRequest(), ctx.getResponse(),
				(InetSocketAddress)((TCPClient)conn.getRemote()).getRemoteAddress(),
				ctx.isThroughProxy());
		} catch (Exception e) {
			conn.getLogger().error("Unexpected error", e);
		}
		
		return handleRedirection();
	}
	
	private boolean handleRedirection() {
		HTTPClientResponse response = ctx.getResponse();
		if (!HTTPResponse.isRedirectionStatusCode(response.getStatusCode()))
			return false;
		if (ctx.getMaxRedirections() <= 0) {
			if (conn.getLogger().debug()) conn.getLogger().debug("No more redirection allowed, handle the response");
			return false;
		}
		
		String location = response.getHeaders().getFirstRawValue(HTTPConstants.Headers.Response.LOCATION);
		if (location == null) {
			if (conn.getLogger().warn()) conn.getLogger().warn("No location given for redirection");
			return false;
		}

		if (conn.getLogger().debug()) conn.getLogger().debug("Redirect to " + location);

		HTTPClientRequestContext c = ctx;
		ctx = null;
		conn.resetStream(this, HTTP2Error.Codes.NO_ERROR);
		
		Task.cpu("Redirect HTTP request", Priority.NORMAL, c.getContext(), t -> {
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
