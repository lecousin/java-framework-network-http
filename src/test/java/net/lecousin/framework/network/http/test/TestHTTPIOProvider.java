package net.lecousin.framework.network.http.test;

import java.net.URL;
import java.nio.ByteBuffer;

import org.junit.Assert;
import org.junit.Test;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.provider.IOProvider;
import net.lecousin.framework.io.provider.IOProviderFromURL;

public class TestHTTPIOProvider extends AbstractHTTPTest {

	@Test(timeout=60000)
	public void test() throws Exception {
		IOProvider p = IOProviderFromURL.getInstance().get(new URL(HTTP_BIN + "get"));
		Assert.assertTrue(p instanceof IOProvider.Readable);
		p.getDescription();
		IO.Readable io = ((IOProvider.Readable)p).provideIOReadable(Task.PRIORITY_NORMAL);
		Assert.assertNotNull(io);
		ByteBuffer buffer = ByteBuffer.allocate(65536);
		io.readFullySync(buffer);
		io.close();
		
		p = IOProviderFromURL.getInstance().get(new URL("http://doesnotexists.com/hello_world"));
		try {
			((IOProvider.Readable)p).provideIOReadable(Task.PRIORITY_NORMAL);
			throw new AssertionError("error");
		} catch (Exception e) {}
	}
	
}
