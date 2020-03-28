package net.lecousin.framework.network.http2.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http.client.HTTPClientRequestSender;
import net.lecousin.framework.network.http1.client.HTTP1ClientConnection;
import net.lecousin.framework.network.http2.frame.HTTP2Settings;
import net.lecousin.framework.util.Pair;

public class HTTP2Client implements HTTPClientRequestSender {
	
	public static final byte[] HTTP1_TO_HTTP2_REQUEST = new byte[] {
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
		manager = new ClientStreamsManager(tcp, settings, null, config.getTimeouts().getSend(), logger, bufferCache);
		// we expect the settings frame to come immediately
		tcp.receiveData(1024, config.getTimeouts().getReceive()).onDone(this::dataReceived);
		manager.getConnectionReady().onDone(ready);
	}
	
	private void dataReceived(ByteBuffer data) {
		manager.consumeDataFromRemote(data).onDone(() -> {
			bufferCache.free(data);
			int size = (int)settings.getMaxFrameSize();
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
		if (logger.debug()) logger.debug("Redirect to " + targetUri);
		// TODO Auto-generated method stub
	}
	
}
