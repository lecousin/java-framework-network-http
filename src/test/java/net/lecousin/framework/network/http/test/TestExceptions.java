package net.lecousin.framework.network.http.test;

import org.junit.Assert;
import org.junit.Test;

import net.lecousin.framework.network.http.exception.HTTPError;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http.test.old.AbstractHTTPTest;

public class TestExceptions extends AbstractHTTPTest {

	@Test
	public void test() {
		Assert.assertEquals(123, new HTTPError(123, "test").getStatusCode());
		Assert.assertEquals(123, new HTTPResponseError(123, "test").getStatusCode());
	}
	
}
