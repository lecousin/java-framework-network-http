package net.lecousin.framework.network.http2.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.encoding.Base64Encoding;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientConnection;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http.client.HTTPClientRequestSender;
import net.lecousin.framework.network.http1.client.HTTP1ClientConnection;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.frame.HTTP2Settings;
import net.lecousin.framework.text.ByteArrayStringIso8859;
import net.lecousin.framework.text.ByteArrayStringIso8859Buffer;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.Triple;

public class HTTP2Client implements HTTPClientRequestSender, AutoCloseable {
	
	private static final byte[] HTTP1_TO_HTTP2_REQUEST = new byte[] {
		'P', 'R', 'I', ' ', '*', ' ', 'H', 'T', 'T', 'P', '/', '2', '.', '0', '\r', '\n',
		'\r', '\n',
		'S', 'M', '\r', '\n',
		'\r', '\n'
	};
	
	private HTTPClientConfiguration config;
	private Logger logger;
	private ByteArrayCache bufferCache;
	private TCPClient tcp;
	private ClientStreamsManager manager;
	private HTTP2Settings settings;
	private Async<IOException> ready = new Async<>();
	
	public HTTP2Client(HTTPClientConfiguration config, HTTP2Settings settings, Logger logger, ByteArrayCache bufferCache) {
		if (config == null) config = new HTTPClientConfiguration();
		this.config = config;
		if (logger == null) logger = LCCore.getApplication().getLoggerFactory().getLogger(HTTP2Client.class);
		this.logger = logger;
		if (bufferCache == null) bufferCache = ByteArrayCache.getInstance();
		this.bufferCache = bufferCache;
		if (settings == null) settings = new HTTP2Settings();
		settings.setEnablePush(false); // for now, we don't want
		this.settings = settings;
	}

	public HTTP2Client(HTTPClientConfiguration config) {
		this(config, null, null, null);
	}
	
	public Async<IOException> connectWithPriorKnowledge(InetSocketAddress address, String hostname, boolean isSecure) {
		Pair<? extends TCPClient, IAsync<IOException>> conn =
			HTTP1ClientConnection.openDirectConnection(address, hostname, isSecure, config, logger);
		tcp = conn.getValue1();
		IAsync<IOException> connect = conn.getValue2();
		connect.thenStart("Start HTTP/2 client connection", Priority.NORMAL, this::startConnectionWithPriorKnowledge, ready);
		return ready;
	}
	
	private void startConnectionWithPriorKnowledge() {
		tcp.send(ByteBuffer.wrap(HTTP1_TO_HTTP2_REQUEST).asReadOnlyBuffer(), config.getTimeouts().getSend());
		manager = new ClientStreamsManager(tcp, settings, false, null, config.getTimeouts().getSend(), logger, bufferCache);
		// we expect the settings frame to come immediately
		tcp.receiveData(1024, config.getTimeouts().getReceive()).onDone(this::dataReceived, ready);
		manager.getConnectionReady().onDone(ready);
	}
	
	public Async<IOException> connectWithUpgrade(InetSocketAddress address, String hostname, boolean isSecure) {
		Pair<? extends TCPClient, IAsync<IOException>> conn =
			HTTP1ClientConnection.openDirectConnection(address, hostname, isSecure, config, logger);
		tcp = conn.getValue1();
		IAsync<IOException> connect = conn.getValue2();
		connect.thenStart("Upgrade to HTTP/2", Priority.NORMAL, () -> upgradeConnection(address, hostname, isSecure), ready);
		return ready;
	}
	
	private void upgradeConnection(InetSocketAddress address, String hostname, boolean isSecure) {
		ByteArray settingsFrame = new HTTP2Settings.Writer(settings, true).produce(2048, bufferCache);
		byte[] settingsBase64 = Base64Encoding.instanceURL.encode(settingsFrame.getArray(),
			HTTP2FrameHeader.LENGTH, settingsFrame.remaining() - HTTP2FrameHeader.LENGTH);
		HTTPClientRequest request = new HTTPClientRequest(hostname, isSecure);
		request.setMethod("OPTIONS")
			.setEncodedPath(new ByteArrayStringIso8859Buffer(new ByteArrayStringIso8859((byte)'*')))
			.addHeader(HTTPConstants.Headers.Request.HOST, hostname
				+ (address.getPort() != HTTPConstants.DEFAULT_HTTP_PORT ? ":" + address.getPort() : "")
			)
			.addHeader(HTTPConstants.Headers.CONNECTION, "Upgrade, HTTP2-Setting")
			.addHeader(HTTPConstants.Headers.Request.UPGRADE, "h2c")
			.addHeader("HTTP2-Settings", new String(settingsBase64, StandardCharsets.US_ASCII));
		
		// send HTTP request
		HTTP1ClientConnection sender = new HTTP1ClientConnection(tcp, new Async<>(true), 1, config);
		HTTPClientRequestContext ctx = new HTTPClientRequestContext(sender, request);
		ctx.setRequestBody(new Pair<>(Long.valueOf(0), new AsyncProducer.Empty<>()));
		ctx.setOnHeadersReceived(response -> {
			if (logger.debug())
				logger.debug("Response received to upgrade request: " + response.getStatusCode());
			if (response.getStatusCode() != 101) {
				ready.error(new IOException("Server does not support websocket on this address, response received is "
					+ response.getStatusCode()));
				return Boolean.TRUE;
			}
			if (logger.trace())
				logger.trace("HTTP protocol upgraded to h2c");
			manager = new ClientStreamsManager(tcp, settings, true, null, config.getTimeouts().getSend(), logger, bufferCache);
			tcp.receiveData(1024, config.getTimeouts().getReceive()).onDone(this::dataReceived, ready);
			manager.getConnectionReady().onDone(ready);
			return Boolean.FALSE;
		});
		sender.reserve(ctx);
		sender.sendReserved(ctx);
	}
	
	private void dataReceived(ByteBuffer data) {
		if (data == null) {
			// end of data from the server
			return;
		}
		manager.consumeDataFromRemote(data).onDone(() -> {
			bufferCache.free(data);
			int size = (int)settings.getMaxFrameSize() + HTTP2FrameHeader.LENGTH;
			if (size > 65536) size = 65536;
			tcp.receiveData(size, config.getTimeouts().getReceive()).onDone(this::dataReceived);
		});
	}
	
	@Override
	public void send(HTTPClientRequestContext request) {
		manager.send(request);
	}
	
	@Override
	public void redirectTo(HTTPClientRequestContext ctx, URI targetUri) {
		if (HTTPClientConnection.isCompatible(targetUri, tcp, ctx.getRequest().getHostname(), ctx.getRequest().getPort())) {
			if (logger.debug()) logger.debug("Reuse same HTTP/2 connection for redirection to " + targetUri);
			send(ctx);
			return;
		}
		if (logger.debug()) logger.debug("Open new connection for redirection to " + targetUri);
		Triple<? extends TCPClient, IAsync<IOException>, Boolean> newClient;
		try {
			newClient = HTTP1ClientConnection.openConnection(targetUri.getHost(), targetUri.getPort(),
				HTTPConstants.HTTPS_SCHEME.equalsIgnoreCase(targetUri.getScheme()),
				targetUri.getPath(), config, logger);
		} catch (Exception e) {
			ctx.getRequestSent().error(IO.error(e));
			return;
		}
		HTTP1ClientConnection newConn = new HTTP1ClientConnection(newClient.getValue1(), newClient.getValue2(), 1, config);
		ctx.setThroughProxy(newClient.getValue3().booleanValue());
		newConn.send(ctx);
		ctx.getResponse().getTrailersReceived().onDone(newConn::close);
	}
	
	@Override
	public void close() throws Exception {
		tcp.close();
	}
	
	public ClientStreamsManager getStreamsManager() {
		return manager;
	}
	
	public TCPClient getConnection() {
		return tcp;
	}
	
}
