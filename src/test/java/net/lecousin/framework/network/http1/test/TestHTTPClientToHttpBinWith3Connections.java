package net.lecousin.framework.network.http1.test;

import java.util.Collection;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.core.test.runners.LCConcurrentRunner;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http.test.requests.HttpBin;
import net.lecousin.framework.network.http.test.requests.TestRequest;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameters;

@RunWith(LCConcurrentRunner.Parameterized.class)
public class TestHTTPClientToHttpBinWith3Connections extends LCCoreAbstractTest {

	@Parameters(name = "{0}")
	public static Collection<Object[]> parameters() {
		return TestRequest.toParameters(HttpBin.getTestRequest());
	}
	
	private static HTTPClient client;
	
	@BeforeClass
	public static void initClient() {
		HTTPClientConfiguration config = new HTTPClientConfiguration();
		config.getLimits().setConnectionsToServer(3);
		config.getLimits().setOpenConnections(3);
		client = new HTTPClient(config);
	}
	
	@AfterClass
	public static void closeClose() {
		client.close();
	}
	
	public TestHTTPClientToHttpBinWith3Connections(TestRequest test) {
		this.test = test;
	}
	
	private TestRequest test;
	
	@Before
	public void initTest() throws Exception {
		test.init();
	}
	
	@Test
	public void test() throws Exception {
		HTTPClientRequestContext ctx = new HTTPClientRequestContext(client, test.request);
		ctx.setMaxRedirections(test.maxRedirection);
		client.send(ctx);
		ctx.getResponse().getBodyReceived().blockThrow(0);
		test.check(ctx.getResponse(), null);
	}
}
