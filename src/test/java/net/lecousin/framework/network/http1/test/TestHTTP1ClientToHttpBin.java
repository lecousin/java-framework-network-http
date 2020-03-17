package net.lecousin.framework.network.http1.test;

import java.util.Collection;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.core.test.runners.LCConcurrentRunner;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientResponse;
import net.lecousin.framework.network.http.test.requests.HttpBin;
import net.lecousin.framework.network.http.test.requests.TestRequest;
import net.lecousin.framework.network.http1.client.HTTP1ClientUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameters;

@RunWith(LCConcurrentRunner.Parameterized.class)
public class TestHTTP1ClientToHttpBin extends LCCoreAbstractTest {

	@Parameters(name = "{0}")
	public static Collection<Object[]> parameters() {
		return TestRequest.toParameters(HttpBin.getTestRequest());
	}
	
	public TestHTTP1ClientToHttpBin(TestRequest test) {
		this.test = test;
	}
	
	private TestRequest test;
	
	@Before
	public void initTest() throws Exception {
		test.init();
	}
	
	@Test
	public void test() throws Exception {
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(TestHTTP1ClientToHttpBin.class);
		//logger.setLevel(Level.TRACE);
		HTTPClientConfiguration config = new HTTPClientConfiguration();
		HTTPClientResponse response = new HTTPClientResponse();
		HTTP1ClientUtil.sendAndReceive(test.request, response, test.maxRedirection, null, null, null, config, logger);
		response.getTrailersReceived().blockThrow(0);
		test.check(response, null);
	}
}
