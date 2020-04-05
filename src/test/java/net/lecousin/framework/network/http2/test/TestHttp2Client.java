package net.lecousin.framework.network.http2.test;

import java.net.InetSocketAddress;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http2.client.HTTP2Client;
import net.lecousin.framework.network.http2.frame.HTTP2Settings;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;

import org.junit.Assert;
import org.junit.Test;


public class TestHttp2Client extends LCCoreAbstractTest {

	@Test
	public void testWikipedia() throws Exception {
		String hostname = "wikipedia.org";
//		AbstractNetworkTest.activateNetworkTraces();
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(TestHttp2Client.class);
		try (HTTP2Client client = new HTTP2Client(new HTTPClientConfiguration(), new HTTP2Settings(), logger, ByteArrayCache.getInstance())) {
			client.connectWithPriorKnowledge(new InetSocketAddress(hostname, 443), hostname, true).blockThrow(0);
			HTTPClientRequest request = new HTTPClientRequest(hostname, 443, true).get("/");
			HTTPClientRequestContext ctx = new HTTPClientRequestContext(client, request);
			ctx.setEntityFactory(BinaryEntity::new);
			client.send(ctx);
			ctx.getResponse().getTrailersReceived().blockThrow(0);
			//System.out.println(ctx.getResponse().getHeaders().generateString(1024).asString());
			Assert.assertTrue(HTTPResponse.isRedirectionStatusCode(ctx.getResponse().getStatusCode()));
		
			request = new HTTPClientRequest(hostname, 443, true).get("/");
			ctx = new HTTPClientRequestContext(client, request);
			ctx.setEntityFactory(BinaryEntity::new);
			ctx.setMaxRedirections(5);
			client.send(ctx);
			ctx.getResponse().getTrailersReceived().blockThrow(0);
			//System.out.println(ctx.getResponse().getHeaders().generateString(1024).asString());
			Assert.assertEquals(200, ctx.getResponse().getStatusCode());
			ParameterizedHeaderValue ct = ctx.getResponse().getHeaders().getContentType();
			Assert.assertEquals("text/html", ct.getMainValue());
//			Charset charset = ct.getParameter("charset") != null ? Charset.forName(ct.getParameter("charset")) : StandardCharsets.ISO_8859_1;
//			CharArrayStringBuffer content = IOUtil.readFullyAsString(((BinaryEntity)ctx.getResponse().getEntity()).getContent(), charset, Priority.NORMAL).blockResult(0);
//			System.out.println(content.asString());
		}
	}

	@Test
	public void testW3Get() throws Exception {
		String hostname = "validator.w3.org";
//		AbstractNetworkTest.activateNetworkTraces();
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(TestHttp2Client.class);
		try (HTTP2Client client = new HTTP2Client(new HTTPClientConfiguration(), new HTTP2Settings(), logger, ByteArrayCache.getInstance())) {
			client.connectWithPriorKnowledge(new InetSocketAddress(hostname, 443), hostname, true).blockThrow(0);
			HTTPClientRequest request = new HTTPClientRequest(hostname, 443, true).get("/");
			HTTPClientRequestContext ctx = new HTTPClientRequestContext(client, request);
			ctx.setEntityFactory(BinaryEntity::new);
			client.send(ctx);
			ctx.getResponse().getTrailersReceived().blockThrow(0);
			//System.out.println(ctx.getResponse().getHeaders().generateString(1024).asString());
			Assert.assertEquals(200, ctx.getResponse().getStatusCode());
			ParameterizedHeaderValue ct = ctx.getResponse().getHeaders().getContentType();
			Assert.assertEquals("text/html", ct.getMainValue());
			//Charset charset = ct.getParameter("charset") != null ? Charset.forName(ct.getParameter("charset")) : StandardCharsets.ISO_8859_1;
			//CharArrayStringBuffer content = IOUtil.readFullyAsString(((BinaryEntity)ctx.getResponse().getEntity()).getContent(), charset, Priority.NORMAL).blockResult(0);
			//System.out.println(content.asString());
		}
	}

//	@Test
//	public void testW3Post() throws Exception {
//		String hostname = "validator.w3.org";
////		AbstractNetworkTest.activateNetworkTraces();
//		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(TestHttp2Client.class);
//		try (HTTP2Client client = new HTTP2Client(new HTTPClientConfiguration(), new HTTP2Settings(), logger, ByteArrayCache.getInstance())) {
//			client.connectWithPriorKnowledge(new InetSocketAddress(hostname, 443), hostname, true).blockThrow(0);
//			FormDataEntity form = new FormDataEntity();
//			form.addField("fragment", "<!DOCTYPE html><html lang=\"en\"><head></head><body></body></html>", StandardCharsets.UTF_8);
//			form.addField("prefill", "0", StandardCharsets.UTF_8);
//			form.addField("doctype", "Inline", StandardCharsets.UTF_8);
//			form.addField("prefill_doctype", "html401", StandardCharsets.UTF_8);
//			form.addField("group", "0", StandardCharsets.UTF_8);
//			HTTPClientRequest request = new HTTPClientRequest(hostname, 443, true).post(form);
//			request.setDecodedPath("/nu/");
//			HTTPClientRequestContext ctx = new HTTPClientRequestContext(client, request);
//			ctx.setEntityFactory(BinaryEntity::new);
//			client.send(ctx);
//			ctx.getResponse().getTrailersReceived().blockThrow(0);
//			//System.out.println(ctx.getResponse().getHeaders().generateString(1024).asString());
//			Assert.assertEquals(200, ctx.getResponse().getStatusCode());
//			ParameterizedHeaderValue ct = ctx.getResponse().getHeaders().getContentType();
//			Assert.assertEquals("text/html", ct.getMainValue());
//			//Charset charset = ct.getParameter("charset") != null ? Charset.forName(ct.getParameter("charset")) : StandardCharsets.ISO_8859_1;
//			//CharArrayStringBuffer content = IOUtil.readFullyAsString(((BinaryEntity)ctx.getResponse().getEntity()).getContent(), charset, Priority.NORMAL).blockResult(0);
//			//System.out.println(content.asString());
//		}
//	}
	
}
