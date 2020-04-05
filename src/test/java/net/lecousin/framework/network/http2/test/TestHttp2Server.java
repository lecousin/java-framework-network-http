package net.lecousin.framework.network.http2.test;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.network.http.client.HTTPClientRequestSender;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;
import net.lecousin.framework.network.http.test.AbstractTestHttpServer;
import net.lecousin.framework.network.http1.server.HTTP1ServerProtocol;
import net.lecousin.framework.network.http2.client.HTTP2Client;
import net.lecousin.framework.network.http2.server.HTTP2ServerProtocol;
import net.lecousin.framework.network.server.protocol.ServerProtocol;

import org.junit.Before;

public class TestHttp2Server extends AbstractTestHttpServer {

	public TestHttp2Server(boolean useSSL) {
		super(useSSL);
	}

	private HTTP1ServerProtocol protocol1;
	private HTTP2ServerProtocol protocol2;
	
	@Before
	public void configureLogs() {
		resumeLogging();
	}
	
	@Override
	protected void stopLogging() {
		LCCore.getApplication().getLoggerFactory().getLogger(HTTP1ServerProtocol.class).setLevel(Level.ERROR);
		LCCore.getApplication().getLoggerFactory().getLogger(HTTP2ServerProtocol.class).setLevel(Level.ERROR);
		LCCore.getApplication().getLoggerFactory().getLogger(HTTP2Client.class).setLevel(Level.ERROR);
	}
	
	@Override
	protected void resumeLogging() {
		LCCore.getApplication().getLoggerFactory().getLogger(HTTP1ServerProtocol.class).setLevel(Level.DEBUG);
		LCCore.getApplication().getLoggerFactory().getLogger(HTTP2ServerProtocol.class).setLevel(useSSL ? Level.DEBUG : Level.TRACE);
		LCCore.getApplication().getLoggerFactory().getLogger(HTTP2Client.class).setLevel(useSSL ? Level.DEBUG : Level.TRACE);
	}
	
	@Override
	protected ServerProtocol createProtocol(HTTPRequestProcessor processor) {
		protocol1 = new HTTP1ServerProtocol(processor);
		protocol2 = new HTTP2ServerProtocol(protocol1);
		return protocol1;
	}
	
	@Override
	protected void enableRangeRequests() {
		protocol2.enableRangeRequests(true);
	}
	
	@Override
	protected HTTPClientRequestSender createClient() throws Exception {
		HTTP2Client client = new HTTP2Client(clientConfig);
		client.connectWithPriorKnowledge(serverAddress, "localhost", useSSL).blockThrow(0);
		return client;
	}
	
}
