package net.lecousin.framework.network.http.test;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.network.http.exception.HTTPError;
import net.lecousin.framework.network.http.exception.HTTPResponseError;

import org.junit.Assert;
import org.junit.Test;

public class TestExceptions extends LCCoreAbstractTest {

	@Test
	public void test() {
		Assert.assertEquals(123, new HTTPError(123, "test").getStatusCode());
		Assert.assertEquals(123, new HTTPResponseError(123, "test").getStatusCode());
	}
	
}
