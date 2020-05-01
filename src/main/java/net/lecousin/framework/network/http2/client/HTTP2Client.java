package net.lecousin.framework.network.http2.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.encoding.Base64Encoding;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientConnection;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http1.client.HTTP1ClientConnection;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.frame.HTTP2Settings;
import net.lecousin.framework.network.ssl.SSLConnectionConfig;
import net.lecousin.framework.text.ByteArrayStringIso8859;
import net.lecousin.framework.text.ByteArrayStringIso8859Buffer;
import net.lecousin.framework.util.Pair;

public class HTTP2Client extends HTTPClientConnection {
	
	private static final byte[] HTTP2_PREFACE = new byte[] {
		'P', 'R', 'I', ' ', '*', ' ', 'H', 'T', 'T', 'P', '/', '2', '.', '0', '\r', '\n',
		'\r', '\n',
		'S', 'M', '\r', '\n',
		'\r', '\n'
	};
	
	private HTTPClientConfiguration config;
	private Logger logger;
	private ByteArrayCache bufferCache;
	private ClientStreamsManager manager;
	private HTTP2Settings settings;
	private List<HTTPClientRequestContext> pendingRequests = new LinkedList<>();
	private long idleStart = System.currentTimeMillis();
	
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
	
	public HTTP2Client(TCPClient client, Async<IOException> connect, boolean isThroughProxy, HTTPClientConfiguration config) {
		this(config, null, null, null);
		Async<IOException> ready = new Async<>();
		setConnection(client, ready, isThroughProxy);
		connect.thenStart("Start HTTP/2 client connection", Priority.NORMAL, this::startConnectionWithPriorKnowledge, ready);
	}

	public HTTP2Client(HTTPClientConfiguration config) {
		this(config, null, null, null);
	}
	
	@Override
	public boolean hasPendingRequest() {
		return !pendingRequests.isEmpty();
	}
	
	@Override
	public boolean isAvailableForReuse() {
		return manager.getLastLocalStreamId() + pendingRequests.size() * 2 < 0x7FFFFF00;
	}
	
	@Override
	public long getIdleTime() {
		return idleStart;
	}
	
	@Override
	public String getDescription() {
		if (idleStart > 0)
			return "Idle since " + ((System.currentTimeMillis() - idleStart) / 1000) + "s.";
		return pendingRequests.size() + " pending request(s)";
	}
	
	@Override
	public void reserve(HTTPClientRequestContext reservedFor) {
		synchronized (pendingRequests) {
			pendingRequests.add(reservedFor);
		}
	}
	
	@Override
	public AsyncSupplier<Boolean, NoException> sendReserved(HTTPClientRequestContext request) {
		// TODO be able to say no if connection is closing
		// TODO check it is reserved
		if (connect.isSuccessful()) {
			manager.send(request);
			followRequest(request);
			return new AsyncSupplier<>(Boolean.TRUE, null);
		}
		AsyncSupplier<Boolean, NoException> result = new AsyncSupplier<>();
		connect.onDone(() -> {
			if (!connect.isSuccessful())
				result.unblockSuccess(Boolean.FALSE);
			else {
				manager.send(request);
				followRequest(request);
				result.unblockSuccess(Boolean.TRUE);
			}
		});
		return result;
	}
	
	@Override
	public void send(HTTPClientRequestContext request) {
		// TODO if not yet connected
		idleStart = -1;
		reserve(request);
		sendReserved(request);
	}
	
	private void followRequest(HTTPClientRequestContext request) {
		request.getResponse().getTrailersReceived().onDone(() -> {
			synchronized (pendingRequests) {
				pendingRequests.remove(request);
				if (pendingRequests.isEmpty())
					idleStart = System.currentTimeMillis();
			}
		});
	}
	
	public Async<IOException> connectWithALPN(InetSocketAddress address, String hostname) {
		SSLConnectionConfig sslConfig = new SSLConnectionConfig();
		sslConfig.setContext(config.getSSLContext());
		sslConfig.setHostNames(Arrays.asList(hostname));
		sslConfig.setApplicationProtocols(Arrays.asList("h2"));
		SSLClient client = new SSLClient(sslConfig);
		Async<IOException> connect = client.connect(address, config.getTimeouts().getConnection(), config.getSocketOptionsArray());
		Async<IOException> ready = new Async<>();
		setConnection(client, ready, false);
		connect.thenStart("Start HTTP/2 client connection", Priority.NORMAL, this::startConnection, ready);
		return ready;
	}
	
	private void startConnection() {
		if (!"h2".equals(((SSLClient)tcp).getApplicationProtocol())) {
			connect.error(new IOException("Remote server does not support h2 protocol using ALPN"));
			tcp.close();
			return;
		}
		startConnectionWithPriorKnowledge();
	}

	public Async<IOException> connectWithPriorKnowledge(InetSocketAddress address, String hostname, boolean isSecure) {
		TCPClient client;
		if (isSecure) {
			SSLConnectionConfig sslConfig = new SSLConnectionConfig();
			sslConfig.setHostNames(Arrays.asList(hostname));
			sslConfig.setContext(config.getSSLContext());
			client = new SSLClient(sslConfig);
		} else {
			client = new TCPClient();
		}
		Async<IOException> connect = client.connect(address, config.getTimeouts().getConnection(), config.getSocketOptionsArray());
		Async<IOException> ready = new Async<>();
		setConnection(client, ready, false);
		connect.thenStart("Start HTTP/2 client connection", Priority.NORMAL, this::startConnectionWithPriorKnowledge, ready);
		return ready;
	}
	
	private void startConnectionWithPriorKnowledge() {
		tcp.send(ByteBuffer.wrap(HTTP2_PREFACE).asReadOnlyBuffer(), config.getTimeouts().getSend());
		manager = new ClientStreamsManager(tcp, settings, false, null, config.getTimeouts().getSend(), logger, bufferCache);
		// we expect the settings frame to come immediately
		tcp.receiveData(1024, config.getTimeouts().getReceive()).onDone(this::dataReceived, connect);
		manager.getConnectionReady().onDone(connect);
	}
	
	public Async<IOException> connectWithUpgrade(InetSocketAddress address, String hostname, boolean isSecure) {
		TCPClient client;
		if (isSecure) {
			SSLConnectionConfig sslConfig = new SSLConnectionConfig();
			sslConfig.setHostNames(Arrays.asList(hostname));
			sslConfig.setContext(config.getSSLContext());
			client = new SSLClient(sslConfig);
		} else {
			client = new TCPClient();
		}
		Async<IOException> connect = client.connect(address, config.getTimeouts().getConnection(), config.getSocketOptionsArray());
		Async<IOException> ready = new Async<>();
		setConnection(client, ready, false);
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
		HTTP1ClientConnection sender = new HTTP1ClientConnection(tcp, new Async<>(true), isThroughProxy, 1, config);
		HTTPClientRequestContext ctx = new HTTPClientRequestContext(sender, request);
		ctx.setRequestBody(new Pair<>(Long.valueOf(0), new AsyncProducer.Empty<>()));
		ctx.setOnHeadersReceived(response -> {
			if (logger.debug())
				logger.debug("Response received to upgrade request: " + response.getStatusCode());
			if (response.getStatusCode() != 101) {
				connect.error(new IOException("Server does not support the upgrade requested, response received is "
					+ response.getStatusCode()));
				return Boolean.TRUE;
			}
			if (logger.trace())
				logger.trace("HTTP protocol upgraded to h2c");
			manager = new ClientStreamsManager(tcp, settings, true, null, config.getTimeouts().getSend(), logger, bufferCache);
			tcp.receiveData(1024, config.getTimeouts().getReceive()).onDone(this::dataReceived, connect);
			manager.getConnectionReady().onDone(connect);
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
	public void redirectTo(HTTPClientRequestContext ctx, URI targetUri) {
		if (HTTPClientConnection.isCompatible(targetUri, tcp, ctx.getRequest().getHostname(), ctx.getRequest().getPort())) {
			if (logger.debug()) logger.debug("Reuse same HTTP/2 connection for redirection to " + targetUri);
			send(ctx);
			return;
		}
		if (logger.debug()) logger.debug("Open new connection for redirection to " + targetUri);
		AsyncSupplier<HTTPClientConnection, IOException> newConnection;
		try {
			newConnection = HTTPClientConnection.openHTTPClientConnection(targetUri.getHost(), targetUri.getPort(),targetUri.getPath(),
				HTTPConstants.HTTPS_SCHEME.equals(targetUri.getScheme()), config, logger);
		} catch (Exception e) {
			ctx.getRequestSent().error(IO.error(e));
			return;
		}
		newConnection.onError(ctx.getRequestSent()::error);
		newConnection.onSuccess(() -> {
			ctx.setThroughProxy(newConnection.getResult().isThroughProxy());
			newConnection.getResult().send(ctx);
			ctx.getResponse().getTrailersReceived().onDone(newConnection.getResult()::close);
		});
	}
	
	public ClientStreamsManager getStreamsManager() {
		return manager;
	}
	
	public TCPClient getConnection() {
		return tcp;
	}
	
}
