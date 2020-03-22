package net.lecousin.framework.network.http1.test;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientResponse;

import org.junit.Assert;
import org.junit.Test;

public class TestCommonHTTPClientToGoogle extends LCCoreAbstractTest {

	@Test
	public void test() throws Exception {
		HTTPClientRequest request = new HTTPClientRequest("cloudsearch.googleapis.com", 443, true).get("/v1/query/sources");
		HTTPClientResponse response = HTTPClient.getDefault().send(request);
		response.getBodyReceived().blockThrow(0);
		Assert.assertEquals(401, response.getStatusCode());

		// second time, we may use another protocol
		request = new HTTPClientRequest("cloudsearch.googleapis.com", 443, true).get("/v1/query/sources");
		response = HTTPClient.getDefault().send(request);
		response.getBodyReceived().blockThrow(0);
		Assert.assertEquals(401, response.getStatusCode());
		// TODO check if we used another protocol
	}
	
}
