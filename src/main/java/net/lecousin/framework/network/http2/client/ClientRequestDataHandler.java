package net.lecousin.framework.network.http2.client;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http2.HTTP2PseudoHeaderHandler;
import net.lecousin.framework.network.http2.streams.DataHandler;
import net.lecousin.framework.network.mime.entity.DefaultMimeEntityFactory;
import net.lecousin.framework.network.mime.entity.EmptyEntity;
import net.lecousin.framework.network.mime.entity.MimeEntity;
import net.lecousin.framework.network.mime.entity.MimeEntityFactory;
import net.lecousin.framework.network.mime.header.MimeHeaders;

class ClientRequestDataHandler implements DataHandler {
	
	public ClientRequestDataHandler(HTTPClientRequestContext ctx) {
		this.ctx = ctx;
	}
	
	private HTTPClientRequestContext ctx;

	@Override
	public MimeHeaders getReceivedHeaders() {
		return ctx.getResponse().getHeaders();
	}

	@Override
	public HTTP2PseudoHeaderHandler createPseudoHeaderHandler() {
		return new HTTP2PseudoHeaderHandler.Response(ctx.getResponse());
	}

	@Override
	public void emptyEntityReceived() {
		ctx.getResponse().setEntity(new EmptyEntity(null, ctx.getResponse().getHeaders()));
	}

	@Override
	public AsyncConsumer<ByteBuffer, IOException> endOfHeaders() throws Exception {
		// TODO handle headers and redirection
		ctx.getResponse().getHeadersReceived().unblock();
		if (ctx.getResponse().getEntity() == null) {
			MimeEntityFactory factory = ctx.getEntityFactory();
			if (factory == null) factory = DefaultMimeEntityFactory.getInstance();
			MimeEntity entity = factory.create(null, ctx.getResponse().getHeaders());
			ctx.getResponse().setEntity(entity);
		}
		// TODO get length
		AsyncConsumer<ByteBuffer, IOException> bodyConsumer = ctx.getResponse().getEntity().createConsumer(null);
		return bodyConsumer;
	}
	
	@Override
	public void endOfBody() {
		ctx.getResponse().getBodyReceived().unblock();
	}
	
	@Override
	public void endOfTrailers() {
		ctx.getResponse().getTrailersReceived().unblock();
	}

}
