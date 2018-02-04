package net.lecousin.framework.network.http.test;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.log.LoggerFactory;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.server.HTTPServerProtocol;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.test.AbstractNetworkTest;

import org.junit.BeforeClass;

public class AbstractHTTPTest extends AbstractNetworkTest {

	@BeforeClass
	public static void initHTTPTests() {
		LoggerFactory lf = LCCore.getApplication().getLoggerFactory();
		lf.getLogger(HTTPRequest.class).setLevel(Level.TRACE);
		lf.getLogger(HTTPResponse.class).setLevel(Level.TRACE);
		lf.getLogger(MimeMessage.class).setLevel(Level.TRACE);
		lf.getLogger(HTTPServerProtocol.class).setLevel(Level.TRACE);
	}
	
}
