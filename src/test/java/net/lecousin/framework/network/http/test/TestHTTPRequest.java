package net.lecousin.framework.network.http.test;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPRequest.Method;
import net.lecousin.framework.network.http.HTTPRequest.Protocol;
import net.lecousin.framework.util.UnprotectedString;

import org.junit.Assert;
import org.junit.Test;

public class TestHTTPRequest extends LCCoreAbstractTest {

	@Test(timeout=30000)
	public void test() throws Exception {
		Assert.assertEquals(Protocol.HTTP_1_0, Protocol.from("HTTP/1.0"));
		Assert.assertEquals(Protocol.HTTP_1_1, Protocol.from("HTTP/1.1"));
		Assert.assertNull(Protocol.from("HTTP/4.5"));
		
		HTTPRequest r = new HTTPRequest();
		r.setAttribute("test", "yes");
		Assert.assertEquals("yes", r.getAttribute("test"));
		Assert.assertTrue(r.hasAttribute("test"));
		Assert.assertEquals("yes", r.removeAttribute("test"));
		Assert.assertNull(r.getAttribute("test"));
		Assert.assertFalse(r.hasAttribute("test"));
		
		Assert.assertFalse(r.isCommandSet());
		Assert.assertNull(r.getParameter("toto"));
		
		r.setCommand(Method.GET, "/toto/titi", Protocol.HTTP_1_0);
		Assert.assertTrue(r.isCommandSet());
		Assert.assertEquals(Method.GET, r.getMethod());
		Assert.assertEquals(Protocol.HTTP_1_0, r.getProtocol());
		Assert.assertEquals("/toto/titi", r.getPath());

		r.setMethod(Method.POST);
		Assert.assertEquals(Method.POST, r.getMethod());
		
		r.setProtocol(Protocol.HTTP_1_1);
		Assert.assertEquals(Protocol.HTTP_1_1, r.getProtocol());

		r.setPath("/hello/world");
		Assert.assertEquals("/hello/world", r.getPath());
		
		r.setCommand("GET /toto/titi?test=tata HTTP/1.0");
		Assert.assertEquals(Method.GET, r.getMethod());
		Assert.assertEquals(Protocol.HTTP_1_0, r.getProtocol());
		Assert.assertEquals("/toto/titi", r.getPath());
		Assert.assertEquals(1, r.getParameters().size());
		Assert.assertEquals("tata", r.getParameter("test"));
		
		r.setCommand("DELETE /hello/world?p1=v1&p2=v2&p3=v3&p+4=%3E4 HTTP/1.1");
		Assert.assertEquals(Method.DELETE, r.getMethod());
		Assert.assertEquals(Protocol.HTTP_1_1, r.getProtocol());
		Assert.assertEquals("/hello/world", r.getPath());
		Assert.assertEquals(4, r.getParameters().size());
		Assert.assertEquals("v1", r.getParameter("p1"));
		Assert.assertEquals("v2", r.getParameter("p2"));
		Assert.assertEquals("v3", r.getParameter("p3"));
		Assert.assertEquals(">4", r.getParameter("p 4"));
		StringBuilder sb = new StringBuilder();
		r.generateFullPath(sb);
		Assert.assertEquals("/hello/world?p1=v1&p2=v2&p3=v3&p+4=%3E4", sb.toString());
		UnprotectedString us = new UnprotectedString(64);
		r.generateFullPath(us);
		Assert.assertEquals("/hello/world?p1=v1&p2=v2&p3=v3&p+4=%3E4", us.toString());
		
		r.getMIME().addHeaderRaw("Cookie", "toto=titi; hello=\"bonjour\"; \"the world\"=\"le monde\"");
		Assert.assertEquals("titi", r.getCookie("toto"));
		Assert.assertEquals("bonjour", r.getCookie("hello"));
		Assert.assertEquals("le monde", r.getCookie("the world"));
		Assert.assertNull(r.getCookie("titi"));

		r.getMIME().addHeaderRaw("Cookie", "toto=tutu; toto=tata");
		Assert.assertEquals(3, r.getCookies("toto").size());
	}
	
}
