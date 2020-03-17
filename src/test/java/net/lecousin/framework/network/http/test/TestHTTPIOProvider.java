package net.lecousin.framework.network.http.test;

import java.net.URI;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.provider.IOProvider;
import net.lecousin.framework.io.provider.IOProviderFromURI;
import net.lecousin.framework.network.http.test.requests.HttpBin;

import org.junit.Assert;
import org.junit.Test;

public class TestHTTPIOProvider extends LCCoreAbstractTest {

	@Test
	public void test() throws Exception {
		IOProvider p = IOProviderFromURI.getInstance().get(new URI("http://" + HttpBin.HTTP_BIN_DOMAIN + "/get"));
		Assert.assertTrue(p instanceof IOProvider.Readable);
		p.getDescription();
		IO.Readable io = ((IOProvider.Readable)p).provideIOReadable(Task.Priority.NORMAL);
		Assert.assertNotNull(io);
		ByteBuffer buffer = ByteBuffer.allocate(65536);
		io.readFullySync(buffer);
		io.close();
		
		p = IOProviderFromURI.getInstance().get(new URI("http://doesnotexists.com/hello_world"));
		try {
			((IOProvider.Readable)p).provideIOReadable(Task.Priority.NORMAL);
			throw new AssertionError("error");
		} catch (Exception e) {}
	}
	
}
