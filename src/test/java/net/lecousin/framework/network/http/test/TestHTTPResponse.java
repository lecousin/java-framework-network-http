package net.lecousin.framework.network.http.test;

import org.junit.Assert;
import org.junit.Test;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.network.http.server.HTTPServerResponse;

public class TestHTTPResponse extends LCCoreAbstractTest {

	@Test(timeout=30000)
	public void test() {
		HTTPServerResponse r = new HTTPServerResponse();
		r.setStatus(200);
		Assert.assertEquals(200, r.getStatusCode());
		r.setStatus(400);
		Assert.assertEquals(400, r.getStatusCode());
		r.setRawContentType("text/test;charset=utf-8");
		Assert.assertEquals("text/test", r.getMIME().getContentTypeValue());
		r.addCookie("toto", "titi", 0, null, null, false, false);
		r.addCookie("toto2", "titi2", 10, "test", "domain.com", true, true);
		r.noCache();
		r.publicCache(Long.valueOf(10));
		r.publicCache(null);
		r.redirectPerm("http://test.com");
	}
	
}
