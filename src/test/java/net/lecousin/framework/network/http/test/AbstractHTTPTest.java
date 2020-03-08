package net.lecousin.framework.network.http.test;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.log.LoggerFactory;
import net.lecousin.framework.network.NetworkManager;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http1.server.HTTP1ServerProtocol;
import net.lecousin.framework.network.mime.transfer.ChunkedTransfer;
import net.lecousin.framework.network.mime.transfer.IdentityTransfer;
import net.lecousin.framework.network.server.protocol.TunnelProtocol;
import net.lecousin.framework.network.test.AbstractNetworkTest;
import net.lecousin.framework.network.websocket.WebSocketClient;
import net.lecousin.framework.network.websocket.WebSocketServerProtocol;

import org.junit.BeforeClass;

public abstract class AbstractHTTPTest extends AbstractNetworkTest {

	@BeforeClass
	public static void initHTTPTests() {
		LoggerFactory lf = LCCore.getApplication().getLoggerFactory();
		lf.getLogger(HTTPRequest.class).setLevel(Level.TRACE);
		lf.getLogger(HTTPResponse.class).setLevel(Level.TRACE);
		lf.getLogger(HTTP1ServerProtocol.class).setLevel(Level.TRACE);
		lf.getLogger(TunnelProtocol.class).setLevel(Level.TRACE);
		lf.getLogger(HTTPClient.class).setLevel(Level.TRACE);
		lf.getLogger(WebSocketClient.class).setLevel(Level.TRACE);
		lf.getLogger(WebSocketServerProtocol.class).setLevel(Level.TRACE);
		lf.getLogger(ChunkedTransfer.class).setLevel(Level.TRACE);
		lf.getLogger(IdentityTransfer.class).setLevel(Level.TRACE);
		NetworkManager.get().getDataLogger().setLevel(Level.INFO);
		NetworkManager.get().getLogger().setLevel(Level.INFO);
	}
	
	public static final String HTTP_BIN_DOMAIN = "eu.httpbin.org";
	public static final String HTTP_BIN = "http://"+HTTP_BIN_DOMAIN+"/";
	public static final String HTTPS_BIN = "https://eu.httpbin.org/";
	
}
