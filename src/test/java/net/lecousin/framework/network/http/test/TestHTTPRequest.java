package net.lecousin.framework.network.http.test;

import java.nio.charset.StandardCharsets;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.network.http.HTTPProtocolVersion;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http1.HTTP1RequestCommandConsumer;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.text.ByteArrayStringIso8859Buffer;

import org.junit.Assert;
import org.junit.Test;

public class TestHTTPRequest extends LCCoreAbstractTest {

	@Test
	public void testAttributes() {
		HTTPRequest r = new HTTPRequest();
		r.setAttribute("test", "yes");
		Assert.assertEquals("yes", r.getAttribute("test"));
		Assert.assertTrue(r.hasAttribute("test"));
		Assert.assertEquals("yes", r.removeAttribute("test"));
		Assert.assertNull(r.getAttribute("test"));
		Assert.assertFalse(r.hasAttribute("test"));
	}

	@Test
	public void testCommandLineConsumerWithoutParameters() throws Exception {
		HTTPRequest r = new HTTPRequest();
		ByteArray data = new ByteArray("GET /toto/titi HTTP/1.0\r\n123".getBytes(StandardCharsets.US_ASCII));
		Assert.assertTrue(new HTTP1RequestCommandConsumer(r).consume(data).blockResult(0).booleanValue());
		Assert.assertEquals(3, data.remaining());
		Assert.assertEquals(HTTPRequest.METHOD_GET, r.getMethod());
		Assert.assertEquals(1, r.getProtocolVersion().getMajor());
		Assert.assertEquals(0, r.getProtocolVersion().getMinor());
		Assert.assertEquals("/toto/titi", r.getDecodedPath());
		Assert.assertNull(r.getQueryParameter("toto"));
	}

	@Test
	public void testCommandLineConsumerWithParameters() throws Exception {
		HTTPRequest r = new HTTPRequest();
		ByteArray data = new ByteArray("GET /toto/titi?a=bc&d= HTTP/1.0\r\n123".getBytes(StandardCharsets.US_ASCII));
		Assert.assertTrue(new HTTP1RequestCommandConsumer(r).consume(data).blockResult(0).booleanValue());
		Assert.assertEquals(3, data.remaining());
		Assert.assertEquals(HTTPRequest.METHOD_GET, r.getMethod());
		Assert.assertEquals(1, r.getProtocolVersion().getMajor());
		Assert.assertEquals(0, r.getProtocolVersion().getMinor());
		Assert.assertEquals("/toto/titi", r.getDecodedPath());
		Assert.assertEquals("bc", r.getQueryParameter("a"));
		Assert.assertEquals("", r.getQueryParameter("d"));
		Assert.assertNull(r.getQueryParameter("toto"));
	}

	@Test
	public void testCommandLineConsumerWithParameters2() throws Exception {
		HTTPRequest r = new HTTPRequest();
		ByteArray data = new ByteArray("DELETE /hello/world?p1=v1&p2=v2&p3=v3&p+4=%3E4 HTTP/1.1\r\n12345".getBytes(StandardCharsets.US_ASCII));
		Assert.assertTrue(new HTTP1RequestCommandConsumer(r).consume(data).blockResult(0).booleanValue());
		Assert.assertEquals(5, data.remaining());
		Assert.assertEquals(HTTPRequest.METHOD_DELETE, r.getMethod());
		Assert.assertEquals(1, r.getProtocolVersion().getMajor());
		Assert.assertEquals(1, r.getProtocolVersion().getMinor());
		Assert.assertEquals("/hello/world", r.getDecodedPath());
		Assert.assertEquals("v1", r.getQueryParameter("p1"));
		Assert.assertEquals("v2", r.getQueryParameter("p2"));
		Assert.assertEquals("v3", r.getQueryParameter("p3"));
		Assert.assertEquals(">4", r.getQueryParameter("p 4"));
	}
	
	@Test
	public void testSetCommandLine() {
		HTTPRequest r = new HTTPRequest();
		r.setMethod(HTTPRequest.METHOD_POST);
		Assert.assertEquals(HTTPRequest.METHOD_POST, r.getMethod());
		
		r.setProtocolVersion(new HTTPProtocolVersion((byte)1, (byte)1));
		Assert.assertEquals(1, r.getProtocolVersion().getMajor());
		Assert.assertEquals(1, r.getProtocolVersion().getMinor());

		r.setDecodedPath("/hello/world");
		Assert.assertEquals("/hello/world", r.getDecodedPath());
	}
	
	@Test
	public void testCookies() throws Exception {
		HTTPRequest r = new HTTPRequest();
		r.setHeaders(new MimeHeaders());
		r.addHeader("Cookie", "toto=titi; hello=\"bonjour\"; \"the world\"=\"le monde\"");
		Assert.assertEquals("titi", r.getCookie("toto"));
		Assert.assertEquals("bonjour", r.getCookie("hello"));
		Assert.assertEquals("le monde", r.getCookie("the world"));
		Assert.assertNull(r.getCookie("titi"));

		r.addHeader("Cookie", "toto=tutu; toto=tata");
		Assert.assertEquals(3, r.getCookies("toto").size());
	}
	
	@Test
	public void testPathAndQuery() {
		HTTPRequest r = new HTTPRequest();
		Assert.assertNull(r.getDecodedPath());
		Assert.assertNull(r.getQueryParameter("test"));
		Assert.assertEquals(0, r.getQueryParameters().size());
		r.setEncodedPath(new ByteArrayStringIso8859Buffer("/x+y/z%3Aa"));
		Assert.assertEquals("/x y/z:a", r.getDecodedPath());
		Assert.assertEquals("/x y/z:a", r.getDecodedPath());
		r.setDecodedPath("/a b/c:d");
		Assert.assertEquals("/a+b/c%3Ad", r.getEncodedPath().asString());
		Assert.assertEquals("/a+b/c%3Ad", r.getEncodedPath().asString());
		r.setQueryParameter("test", "ok");
		Assert.assertEquals("ok", r.getQueryParameter("test"));
		r.setEncodedQueryString(new ByteArrayStringIso8859Buffer("a=b&c=%3A&d=1"));
		Assert.assertEquals("b", r.getQueryParameter("a"));
		Assert.assertEquals(":", r.getQueryParameter("c"));
		Assert.assertEquals("1", r.getQueryParameter("d"));
		r.setEncodedQueryString(new ByteArrayStringIso8859Buffer("a=b&c=%3A&d=1"));
		Assert.assertEquals(3, r.getQueryParameters().size());
		Assert.assertEquals("b", r.getQueryParameters().get("a"));
		Assert.assertEquals(":", r.getQueryParameters().get("c"));
		Assert.assertEquals("1", r.getQueryParameters().get("d"));
		r.setEncodedQueryString(new ByteArrayStringIso8859Buffer("a=1&b=2"));
		r.setQueryParameter("c", "3");
		Assert.assertEquals("1", r.getQueryParameter("a"));
		Assert.assertEquals("2", r.getQueryParameter("b"));
		Assert.assertEquals("3", r.getQueryParameter("c"));
		r = new HTTPRequest();
		r.setQueryParameter("test", "ok");
		Assert.assertEquals("ok", r.getQueryParameter("test"));
		r = new HTTPRequest();
		r.getQueryParameters();
		Assert.assertEquals("", r.getEncodedQueryString().asString());
		r.setQueryParameter("a", ":");
		Assert.assertEquals("a=%3A", r.getEncodedQueryString().asString());
	}
	
	@Test
	public void testToString() {
		HTTPRequest r = new HTTPRequest();
		r.setMethod("TEST").setDecodedPath("/a:b").setProtocolVersion(new HTTPProtocolVersion((byte)0, (byte)9)).addHeader("X-test", "ok");
		Assert.assertEquals("TEST /a%3Ab HTTP/0.9\r\nX-test: ok\r\n", r.toString());
		r = new HTTPRequest();
		r.setMethod("TEST").setDecodedPath("/a:b").setProtocolVersion(new HTTPProtocolVersion((byte)0, (byte)9));
		Assert.assertEquals("TEST /a%3Ab HTTP/0.9\r\n", r.toString());
	}
	
}
