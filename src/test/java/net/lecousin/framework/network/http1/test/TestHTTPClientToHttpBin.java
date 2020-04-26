package net.lecousin.framework.network.http1.test;

import java.util.Collection;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.core.test.runners.LCConcurrentRunner;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http.test.requests.HttpBin;
import net.lecousin.framework.network.http.test.requests.TestRequest;
import net.lecousin.framework.network.test.AbstractNetworkTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameters;

@RunWith(LCConcurrentRunner.Parameterized.class) @org.junit.runners.Parameterized.UseParametersRunnerFactory(LCConcurrentRunner.ConcurrentParameterizedRunnedFactory.class)
public class TestHTTPClientToHttpBin extends LCCoreAbstractTest {

	@Parameters(name = "{0}")
	public static Collection<Object[]> parameters() {
		return TestRequest.toParameters(HttpBin.getTestRequest());
	}
	
	public TestHTTPClientToHttpBin(TestRequest test) {
		this.test = test;
	}
	
	private TestRequest test;
	
	@Before
	public void initTest() throws Exception {
		AbstractNetworkTest.deactivateNetworkTraces();
		test.init();
	}
	
	@Test
	public void test() throws Exception {
		HTTPClientConfiguration config = new HTTPClientConfiguration();
		try (HTTPClient client = new HTTPClient(config)) {
			client.getDescription();
			HTTPClientRequestContext ctx = new HTTPClientRequestContext(client, test.request);
			ctx.setMaxRedirections(test.maxRedirection);
			client.send(ctx);
			client.getDescription();
			ctx.getResponse().getBodyReceived().blockThrow(0);
			client.getDescription();
			test.check(ctx.getResponse(), null);
		}
	}
}
