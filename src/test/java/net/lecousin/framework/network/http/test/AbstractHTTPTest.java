package net.lecousin.framework.network.http.test;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.log.LoggerFactory;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.server.HTTPServerProtocol;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.test.AbstractNetworkTest;

import org.junit.BeforeClass;

public abstract class AbstractHTTPTest extends AbstractNetworkTest {

	@BeforeClass
	public static void initHTTPTests() {
		LoggerFactory lf = LCCore.getApplication().getLoggerFactory();
		lf.getLogger(HTTPRequest.class).setLevel(Level.TRACE);
		lf.getLogger(HTTPResponse.class).setLevel(Level.TRACE);
		lf.getLogger(MimeMessage.class).setLevel(Level.TRACE);
		lf.getLogger(HTTPServerProtocol.class).setLevel(Level.TRACE);
		lf.getLogger(HTTPClient.class).setLevel(Level.TRACE);
	}
	
	public static final String HTTP_BIN_DOMAIN = "eu.httpbin.org";
	public static final String HTTP_BIN = "http://"+HTTP_BIN_DOMAIN+"/";
	public static final String HTTPS_BIN = "https://eu.httpbin.org/";
	
}
