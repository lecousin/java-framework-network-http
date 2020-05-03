package net.lecousin.framework.network.http.test;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.core.test.runners.LCConcurrentRunner;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration.Protocol;
import net.lecousin.framework.network.http.test.requests.HttpBin;
import net.lecousin.framework.network.http.test.requests.TestRequest;
import net.lecousin.framework.network.ssl.SSLConnectionConfig;
import net.lecousin.framework.network.test.AbstractNetworkTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameters;

@RunWith(LCConcurrentRunner.Parameterized.class) @org.junit.runners.Parameterized.UseParametersRunnerFactory(LCConcurrentRunner.ConcurrentParameterizedRunnedFactory.class)
public class TestCommonHTTPClientToHttpBin extends LCCoreAbstractTest {

	@Parameters(name = "{0} using {1}")
	public static Collection<Object[]> parameters() {
		List<Object[]> parameters = new LinkedList<>();
		for (TestRequest test : HttpBin.getTestRequest())
			parameters.add(new Object[] { test, Protocol.HTTP1 });
		if (SSLConnectionConfig.ALPN_SUPPORTED) {
			for (TestRequest test : HttpBin.getTestRequest())
				parameters.add(new Object[] { test, Protocol.H2 });
		} else {
			for (TestRequest test : HttpBin.getTestRequest())
				parameters.add(new Object[] { test, Protocol.HTTP1S });
		}
		return parameters;
	}
	
	public TestCommonHTTPClientToHttpBin(TestRequest test, Protocol protocol) {
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
		HTTPClient client = HTTPClient.getDefault();
		HTTPClientRequest req = new HTTPClientRequest(test.request);
		if (protocol.isSecure()) {
			req.setSecure(true);
			req.setPort(443);
		}
		HTTPClientRequestContext ctx = new HTTPClientRequestContext(client, req);
		ctx.setMaxRedirections(test.maxRedirection);
		client.send(ctx);
		ctx.getResponse().getBodyReceived().blockThrow(0);
		test.check(req, ctx.getResponse(), null);
	}
}
