package net.lecousin.framework.network.http.test;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.core.test.runners.LCConcurrentRunner;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration.Protocol;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http.test.requests.HttpBin;
import net.lecousin.framework.network.http.test.requests.TestRequest;
import net.lecousin.framework.network.ssl.SSLConnectionConfig;
import net.lecousin.framework.network.test.AbstractNetworkTest;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameters;

@RunWith(LCConcurrentRunner.Parameterized.class) @org.junit.runners.Parameterized.UseParametersRunnerFactory(LCConcurrentRunner.ConcurrentParameterizedRunnedFactory.class)
public class TestHTTPClientToHttpBinWith3Connections extends LCCoreAbstractTest {

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
	
	public TestHTTPClientToHttpBinWith3Connections(TestRequest test, Protocol protocol) {
		this.test = test;
		this.protocol = protocol;
	}
	
	private TestRequest test;
	private Protocol protocol;
	
	private static Map<Protocol, HTTPClient> clients = new HashMap<>();
	
	@AfterClass
	public static void closeClose() {
		for (HTTPClient client : clients.values())
			client.close();
		clients = null;
	}
	
	@Before
	public void initTest() throws Exception {
		AbstractNetworkTest.deactivateNetworkTraces();
		test.init();
	}
	
	@Test
	public void test() throws Exception {
		HTTPClient client;
		synchronized (clients) {
			client = clients.get(protocol);
			if (client == null) {
				HTTPClientConfiguration config = new HTTPClientConfiguration();
				config.setAllowedProtocols(Arrays.asList(protocol));
				config.getLimits().setConnectionsToServer(3);
				config.getLimits().setOpenConnections(3);
				client = new HTTPClient(config);
				clients.put(protocol, client);
			}
		}
		HTTPClientRequestContext ctx = new HTTPClientRequestContext(client, new HTTPClientRequest(test.request));
		ctx.setMaxRedirections(test.maxRedirection);
		client.send(ctx);
		ctx.getResponse().getBodyReceived().blockThrow(60000);
		if (!ctx.getResponse().getBodyReceived().isDone()) {
			StringBuilder s = new StringBuilder();
			s.append("Request ").append(ctx).append(" failed:")
			.append("\n - sent: ").append(ctx.getRequestSent().isDone())
			.append("\n - headers: ").append(ctx.getResponse().getHeadersReceived().isDone())
			.append("\n - body: ").append(ctx.getResponse().getBodyReceived().isDone())
			.append("\n - trailers: ").append(ctx.getResponse().getTrailersReceived().isDone())
			;
			throw new AssertionError(s.toString());
		}
		test.check(ctx.getRequest(), ctx.getResponse(), null);
	}
}
