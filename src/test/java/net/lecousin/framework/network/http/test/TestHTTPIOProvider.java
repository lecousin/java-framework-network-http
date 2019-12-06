package net.lecousin.framework.network.http.test;

import java.net.URI;
import java.nio.ByteBuffer;

import org.junit.Assert;
import org.junit.Test;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.provider.IOProvider;
import net.lecousin.framework.io.provider.IOProviderFromURI;

public class TestHTTPIOProvider extends AbstractHTTPTest {

	@Test
	public void test() throws Exception {
		IOProvider p = IOProviderFromURI.getInstance().get(new URI(HTTP_BIN + "get"));
		Assert.assertTrue(p instanceof IOProvider.Readable);
		p.getDescription();
		IO.Readable io = ((IOProvider.Readable)p).provideIOReadable(Task.PRIORITY_NORMAL);
		Assert.assertNotNull(io);
		ByteBuffer buffer = ByteBuffer.allocate(65536);
		io.readFullySync(buffer);
		io.close();
		
		p = IOProviderFromURI.getInstance().get(new URI("http://doesnotexists.com/hello_world"));
		try {
			((IOProvider.Readable)p).provideIOReadable(Task.PRIORITY_NORMAL);
			throw new AssertionError("error");
		} catch (Exception e) {}
	}
	
}
