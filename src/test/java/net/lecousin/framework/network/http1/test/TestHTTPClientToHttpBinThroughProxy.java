package net.lecousin.framework.network.http1.test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.core.test.runners.LCConcurrentRunner;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http.server.processor.ProxyHTTPRequestProcessor;
import net.lecousin.framework.network.http.test.requests.HttpBin;
import net.lecousin.framework.network.http.test.requests.TestRequest;
import net.lecousin.framework.network.http1.server.HTTP1ServerProtocol;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.test.AbstractNetworkTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameters;

@RunWith(LCConcurrentRunner.Parameterized.class)
public class TestHTTPClientToHttpBinThroughProxy extends LCCoreAbstractTest {

	@Parameters(name = "{0}")
	public static Collection<Object[]> parameters() {
		return TestRequest.toParameters(HttpBin.getTestRequest());
	}
	
	public TestHTTPClientToHttpBinThroughProxy(TestRequest test) {
		this.test = test;
	}
	
	private TestRequest test;
	
	@Before
	public void initTest() throws Exception {
		AbstractNetworkTest.deactivateNetworkTraces();
		test.init();
	}
	
	private TCPServer server;
	private ProxyHTTPRequestProcessor processor;
	private int serverPort;
	
	@Before
	public void startProxy() throws Exception {
		server = new TCPServer();
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger("test-proxy");
		logger.setLevel(Level.TRACE);
		processor = new ProxyHTTPRequestProcessor(HTTPClient.getDefault(), 8192, 10000, 10000, logger);
		HTTP1ServerProtocol protocol = new HTTP1ServerProtocol(processor);
		server.setProtocol(protocol);
		SocketAddress serverAddress = server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);
		serverPort = ((InetSocketAddress)serverAddress).getPort();
		processor.allowForwardFromHttpToHttps(true);
	}
	
	@After
	public void stopProxy() {
		server.close();
	}
	
	@Test
	public void test() throws Exception {
		HTTPClientConfiguration config = new HTTPClientConfiguration();
		config.setProxySelector(new ProxySelector() {
			private Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", serverPort));
			@Override
			public List<Proxy> select(URI uri) {
				return Collections.singletonList(proxy);
			}
			
			@Override
			public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
			}
		});
		try (HTTPClient client = new HTTPClient(config)) {
			HTTPClientRequestContext ctx = new HTTPClientRequestContext(client, test.request);
			ctx.setMaxRedirections(test.maxRedirection);
			client.send(ctx);
			ctx.getResponse().getBodyReceived().blockThrow(0);
			test.check(ctx.getResponse(), null);
		}
	}
}
