package net.lecousin.framework.network.http.test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.core.test.runners.LCConcurrentRunner;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration.Protocol;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http.server.processor.ProxyHTTPRequestProcessor;
import net.lecousin.framework.network.http.test.requests.HttpBin;
import net.lecousin.framework.network.http.test.requests.TestRequest;
import net.lecousin.framework.network.http1.server.HTTP1ServerProtocol;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.ssl.SSLConnectionConfig;
import net.lecousin.framework.network.test.AbstractNetworkTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameters;

@RunWith(LCConcurrentRunner.Parameterized.class) @org.junit.runners.Parameterized.UseParametersRunnerFactory(LCConcurrentRunner.ConcurrentParameterizedRunnedFactory.class)
public class TestHTTPClientToHttpBinThroughProxy extends LCCoreAbstractTest {

	@Parameters(name = "{0} using {1}")
	public static Collection<Object[]> parameters() {
		List<Object[]> parameters = new LinkedList<>();
		for (TestRequest test : HttpBin.getTestRequest())
			parameters.add(new Object[] { test, Protocol.HTTP1 });
		for (TestRequest test : HttpBin.getTestRequest())
			parameters.add(new Object[] { test, Protocol.HTTP1S });
		if (SSLConnectionConfig.ALPN_SUPPORTED)
			for (TestRequest test : HttpBin.getTestRequest())
				parameters.add(new Object[] { test, Protocol.H2 });
		return parameters;
	}
	
	public TestHTTPClientToHttpBinThroughProxy(TestRequest test, Protocol protocol) {
		this.test = test;
		this.protocol = protocol;
	}
	
	private TestRequest test;
	private Protocol protocol;
	
	@Before
	public void initTest() throws Exception {
		AbstractNetworkTest.deactivateNetworkTraces();
		//LoggerFactory.get(HTTP2Client.class).setLevel(Level.TRACE);
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
		HTTPClientRequest req = new HTTPClientRequest(test.request);
		HTTPClientConfiguration config = new HTTPClientConfiguration();
		config.setAllowedProtocols(Arrays.asList(protocol));
		if (protocol.isSecure()) {
			req.setSecure(true);
			req.setPort(443);
		}
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
			HTTPClientRequestContext ctx = new HTTPClientRequestContext(client, req);
			ctx.setMaxRedirections(test.maxRedirection);
			client.send(ctx);
			ctx.getResponse().getBodyReceived().blockThrow(0);
			test.check(req, ctx.getResponse(), null);
		}
	}
}
