package net.lecousin.framework.network.http.test;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.network.http.HTTPResponse;

import org.junit.Assert;
import org.junit.Test;

public class TestHTTPResponse extends LCCoreAbstractTest {

	@Test(timeout=30000)
	public void test() {
		HTTPResponse r = new HTTPResponse();
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
		r.setForceClose(true);
	}
	
}
