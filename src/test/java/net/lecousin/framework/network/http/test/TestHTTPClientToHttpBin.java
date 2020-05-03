package net.lecousin.framework.network.http.test;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameters;

@RunWith(LCConcurrentRunner.Parameterized.class) @org.junit.runners.Parameterized.UseParametersRunnerFactory(LCConcurrentRunner.ConcurrentParameterizedRunnedFactory.class)
public class TestHTTPClientToHttpBin extends LCCoreAbstractTest {

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
	
	public TestHTTPClientToHttpBin(TestRequest test, Protocol protocol) {
		this.test = test;
		this.protocol = protocol;
	}
	
	private TestRequest test;
	private Protocol protocol;
	
	@Before
	public void initTest() throws Exception {
		AbstractNetworkTest.deactivateNetworkTraces();
		test.init();
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
		try (HTTPClient client = new HTTPClient(config)) {
			client.getDescription();
			HTTPClientRequestContext ctx = new HTTPClientRequestContext(client, req);
			ctx.setMaxRedirections(test.maxRedirection);
			client.send(ctx);
			client.getDescription();
			ctx.getResponse().getBodyReceived().blockThrow(0);
			client.getDescription();
			test.check(req, ctx.getResponse(), null);
		}
	}
}
