package net.lecousin.framework.network.http.server.processor;

import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.out2in.OutputToInputBuffers;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http.client.HTTPClientResponse;
import net.lecousin.framework.network.http.client.filters.AcceptEncodingFilter;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.http.server.HTTPServerResponse;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;

/**
 * Forwards HTTP requests to another server, can be typically used by an HTTP proxy.
 */
public class HTTPRequestForwarder {
	
	/** Constructor. */
	public HTTPRequestForwarder(Logger logger, HTTPClient client) {
		this.logger = logger;
		this.client = client;
	}

	protected Logger logger;
	protected HTTPClient client;
	
	/** Forward the request to the given host and port. */
	public void forward(HTTPRequestContext ctx, String host, int port, boolean useSSL) {
		if (logger.debug())
			logger.debug("Forward request " + ctx.getRequest().getDecodedPath() + " to " + host + ":" + port);
		HTTPClientRequest request = new HTTPClientRequest(host, port, useSSL, ctx.getRequest());
		if (ctx.getRequest().getEntity() == null) {
			BinaryEntity entity = new BinaryEntity(null, ctx.getRequest().getHeaders());
			// TODO if size is known, we should be able to send it instead of doing chunked transfer
			entity.setContent(new OutputToInputBuffers(true, 8, Priority.NORMAL));
			ctx.getRequest().setEntity(entity);
			request.setEntity(entity);
		}
		new AcceptEncodingFilter().filter(request, null);
		doForward(request, ctx);
	}
	
	protected void doForward(HTTPClientRequest request, HTTPRequestContext ctx) {
		HTTPServerResponse response = ctx.getResponse();
		HTTPClientRequestContext clientCtx = new HTTPClientRequestContext(client, request);
		clientCtx.setEntityFactory((parent, headers) -> {
			// TODO if size is known, we should be able to send it instead of doing chunked transfer
			BinaryEntity entity = new BinaryEntity(parent, headers);
			OutputToInputBuffers o2i = new OutputToInputBuffers(true, 8, Priority.NORMAL);
			entity.setContent(o2i);
			BinaryEntity respEntity = new BinaryEntity(null, response.getHeaders());
			respEntity.setContent(o2i);
			response.setEntity(respEntity);
			response.getReady().unblock();
			return entity;
		});
		HTTPClientResponse clientResponse = clientCtx.getResponse();
		clientResponse.getHeadersReceived().onDone(() -> {
			response.setStatus(clientResponse.getStatusCode());
			if (logger.trace()) {
				StringBuilder log = new StringBuilder(1024);
				log.append("Request ").append(ctx.getRequest().getDecodedPath()).append(" returned headers:\r\n");
				for (MimeHeader h : clientResponse.getHeaders().getHeaders())
					log.append(h.getName()).append(": ").append(h.getRawValue()).append("\r\n");
				logger.trace(log.toString());
			}
			for (MimeHeader h : clientResponse.getHeaders().getHeaders()) {
				String name = h.getNameLowerCase();
				if (MimeHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) continue;
				if (MimeHeaders.CONTENT_TRANSFER_ENCODING.equalsIgnoreCase(name)) continue;
				if (MimeHeaders.CONTENT_ENCODING.equalsIgnoreCase(name)) continue;
				response.addHeader(new MimeHeader(h.getName(), h.getRawValue()));
			}
		});
		clientResponse.getBodyReceived().onError(error -> {
			response.getReady().error(error);
			if (response.getEntity() instanceof BinaryEntity) {
				((IO.OutputToInput)((BinaryEntity)response.getEntity()).getContent()).signalErrorBeforeEndOfData(error);
			}
		});
		clientResponse.getBodyReceived().onSuccess(() -> {
			if (!(response.getEntity() instanceof BinaryEntity)) {
				response.getReady().unblock();
			}
		});
		client.send(clientCtx);
	}

}
