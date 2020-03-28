package net.lecousin.framework.network.http2.test;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http2.HTTP2PseudoHeaderHandler;
import net.lecousin.framework.network.http2.hpack.HPackCompress;
import net.lecousin.framework.network.http2.hpack.HPackDecompress;

import org.junit.Assert;
import org.junit.Test;

public class TestHPack extends LCCoreAbstractTest {

	@Test
	public void test() throws Exception {
		ByteArray.Writable b = new ByteArray.Writable(new byte[4096], false);
		HPackCompress c = new HPackCompress(4096);
		HPackDecompress d = new HPackDecompress(4096);

		// first request
		c.compress(":method", "GET", true, true, b);
		c.compress(":scheme", "http", true, true, b);
		c.compress(":path", "/", true, true, b);
		c.compress(":authority", "www.example.com", true, true, b);
		
		byte[] expected = new byte[] { (byte)0x82, (byte)0x86, (byte)0x84, 0x41, (byte)0x8c, (byte)0xf1, (byte)0xe3, (byte)0xc2, (byte)0xe5, (byte)0xf2, 0x3a, 0x6b, (byte)0xa0, (byte)0xab, (byte)0x90, (byte)0xf4, (byte)0xff };
		byte[] found = new byte[b.getCurrentArrayOffset()];
		System.arraycopy(b.getArray(), 0, found, 0, b.getCurrentArrayOffset());
		Assert.assertArrayEquals(expected, found);

		HTTPRequest request = new HTTPRequest();
		b.flip();
		d.consume(b.toByteBuffer(), request.getHeaders(), new HTTP2PseudoHeaderHandler.Request(request));
		Assert.assertEquals("GET", request.getMethod());
		Assert.assertEquals("/", request.getDecodedPath());

		// second request
		b = new ByteArray.Writable(new byte[4096], false);
		c.compress(":method", "GET", true, true, b);
		c.compress(":scheme", "http", true, true, b);
		c.compress(":path", "/", true, true, b);
		c.compress(":authority", "www.example.com", true, true, b);
		c.compress("cache-control", "no-cache", true, true, b);
		
		expected = new byte[] { (byte)0x82, (byte)0x86, (byte)0x84, (byte)0xbe, 0x58, (byte)0x86, (byte)0xa8, (byte)0xeb, 0x10, 0x64, (byte)0x9c, (byte)0xbf };
		found = new byte[b.getCurrentArrayOffset()];
		System.arraycopy(b.getArray(), 0, found, 0, b.getCurrentArrayOffset());
		Assert.assertArrayEquals(expected, found);
		
		request = new HTTPRequest();
		b.flip();
		d.consume(b.toByteBuffer(), request.getHeaders(), new HTTP2PseudoHeaderHandler.Request(request));
		Assert.assertEquals("GET", request.getMethod());
		Assert.assertEquals("/", request.getDecodedPath());
		Assert.assertEquals("no-cache", request.getHeaders().getFirstRawValue("cache-control"));

		// third request
		b = new ByteArray.Writable(new byte[4096], false);
		c.compress(":method", "GET", true, true, b);
		c.compress(":scheme", "https", true, true, b);
		c.compress(":path", "/index.html", true, true, b);
		c.compress(":authority", "www.example.com", true, true, b);
		c.compress("custom-key", "custom-value", true, true, b);
		
		expected = new byte[] { (byte)0x82, (byte)0x87, (byte)0x85, (byte)0xbf, 0x40, (byte)0x88, 0x25, (byte)0xa8, 0x49, (byte)0xe9, 0x5b, (byte)0xa9, 0x7d, 0x7f, (byte)0x89, 0x25, (byte)0xa8, 0x49, (byte)0xe9, 0x5b, (byte)0xb8, (byte)0xe8, (byte)0xb4, (byte)0xbf };
		found = new byte[b.getCurrentArrayOffset()];
		System.arraycopy(b.getArray(), 0, found, 0, b.getCurrentArrayOffset());
		Assert.assertArrayEquals(expected, found);
		
		request = new HTTPRequest();
		b.flip();
		d.consume(b.toByteBuffer(), request.getHeaders(), new HTTP2PseudoHeaderHandler.Request(request));
		Assert.assertEquals("GET", request.getMethod());
		Assert.assertEquals("/index.html", request.getDecodedPath());
		Assert.assertEquals("custom-value", request.getHeaders().getFirstRawValue("custom-key"));
	}
	
}
