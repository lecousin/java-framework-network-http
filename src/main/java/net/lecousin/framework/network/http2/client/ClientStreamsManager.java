package net.lecousin.framework.network.http2.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http2.HTTP2Constants;
import net.lecousin.framework.network.http2.frame.HTTP2Headers;
import net.lecousin.framework.network.http2.frame.HTTP2Settings;
import net.lecousin.framework.network.http2.streams.DataHandler;
import net.lecousin.framework.network.http2.streams.DataStreamHandler;
import net.lecousin.framework.network.http2.streams.StreamsManager;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.text.ByteArrayStringIso8859Buffer;
import net.lecousin.framework.util.Pair;

public class ClientStreamsManager extends StreamsManager {

	public ClientStreamsManager(
		TCPClient remote,
		HTTP2Settings localSettings, boolean localSettingsSent, HTTP2Settings initialRemoteSettings,
		int sendTimeout,
		Logger logger, ByteArrayCache bufferCache
	) {
		super(remote, true, localSettings, localSettingsSent, initialRemoteSettings, sendTimeout, logger, bufferCache);
	}
	
	private Map<Integer, ClientRequestDataHandler> dataHandlers = new HashMap<>();
	
	@Override
	public DataHandler createDataHandler(int streamId) {
		synchronized (dataHandlers) {
			return dataHandlers.remove(Integer.valueOf(streamId >> 1));
		}
	}
	
	@Override
	public void close() {
		super.close();
		synchronized (dataHandlers) {
			for (ClientRequestDataHandler h : dataHandlers.values())
				h.close();
			dataHandlers.clear();
		}
	}
	
	@Override
	protected void streamError(int streamId, int error) {
		ClientRequestDataHandler handler;
		synchronized (dataHandlers) {
			handler = dataHandlers.remove(Integer.valueOf(streamId >> 1));
		}
		if (handler != null)
			handler.error(error);
	}
	
	TCPClient getRemote() {
		return (TCPClient)remote;
	}

	void send(HTTPClientRequestContext ctx) {
		Task.cpu("Create HTTP/2 headers frame", (Task<Void, NoException> task) -> {
			if (remote.isClosed() || isClosing()) {
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
		boolean isEndOfStream = false;
		Long size = ctx.getRequestBody().getValue1();
		if (size != null) {
			if (size.longValue() == 0)
				isEndOfStream = true;
			else
				headers.add(new Pair<>("content-length", size.toString()));
		}
		if (logger.trace())
			logger.trace("HTTP/2 headers to send:\n" + headers);
		if (ctx.getRequest().getTrailerHeadersSuppliers() != null)
			isEndOfStream = false;
		// send headers/continuation frames, then body, then trailers
		final boolean eos = isEndOfStream;
		reserveCompressionContextAndOpenStream().thenStart("Send HTTP/2 headers", Priority.NORMAL, reservation -> {
			int streamId = reservation.getValue2().intValue();
			synchronized (dataHandlers) {
				dataHandlers.put(Integer.valueOf(streamId >> 1), new ClientRequestDataHandler(ctx));
			}
			sendFrame(new HTTP2Headers.Writer(streamId, headers, eos, reservation.getValue1(), () -> {
				releaseCompressionContext(streamId);
				DataStreamHandler.sendBody(this, streamId, ctx.getRequest(), ctx.getRequestSent(),
					size, ctx.getRequestBody().getValue2(), 128 * 1024);
			}), false).onError(ctx.getRequestSent()::error);
		}, ctx.getRequestSent());
	}
	
	/** Return the settings returned by the server.
	 * The values MUST NOT be changed.
	 * This method is for test purposes to simulate bad client behavior.
	 */
	public HTTP2Settings getServerSettings() {
		return remoteSettings;
	}

}
