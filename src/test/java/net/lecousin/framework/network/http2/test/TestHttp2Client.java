package net.lecousin.framework.network.http2.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.core.test.runners.LCConcurrentRunner;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http.client.HTTPClientResponse;
import net.lecousin.framework.network.http.test.requests.TestRequest;
import net.lecousin.framework.network.http.test.requests.TestRequest.ResponseChecker;
import net.lecousin.framework.network.http2.client.HTTP2Client;
import net.lecousin.framework.network.http2.frame.HTTP2Settings;
import net.lecousin.framework.network.http2.test.requests.Http2TestRequest;
import net.lecousin.framework.network.http2.test.requests.Http2TestRequest.ConnectMethod;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.entity.TextEntity;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.network.ssl.SSLConnectionConfig;
import net.lecousin.framework.network.test.AbstractNetworkTest;
import net.lecousin.framework.text.CharArrayStringBuffer;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameters;

@RunWith(LCConcurrentRunner.Parameterized.class) @org.junit.runners.Parameterized.UseParametersRunnerFactory(LCConcurrentRunner.ConcurrentParameterizedRunnedFactory.class)
public class TestHttp2Client extends LCCoreAbstractTest {

	@Parameters(name = "{0}")
	public static Collection<Object[]> parameters() {
		return TestRequest.toParameters(getTestRequests());
	}
	
	public static List<Http2TestRequest> getTestRequests() {
		List<Http2TestRequest> list = new LinkedList<>();
		
		// wikipedia.org
		list.add(new Http2TestRequest("simple test", new HTTPClientRequest("wikipedia.org", 443, true).get("/"), ConnectMethod.PRIOR_KNOWLEDGE, 3, new CheckWikipediaHomePage()));

		// validator.w3.org
		list.add(new Http2TestRequest("simple test", new HTTPClientRequest("validator.w3.org", 443, true).get("/"), ConnectMethod.PRIOR_KNOWLEDGE, 3, new CheckW3CValidatorHomePage()));
		list.add(new Http2TestRequest("simple test", new HTTPClientRequest("validator.w3.org", 80, false).get("/"), ConnectMethod.PRIOR_KNOWLEDGE, 3, new CheckW3CValidatorHomePage()));
		
		// http2.golang.org
		list.add(new Http2TestRequest("simple test", new HTTPClientRequest("http2.golang.org", 443, true).get("/reqinfo"), ConnectMethod.ALPN, 3, new CheckGolangReqinfo()));
		
		// google.com
		list.add(new Http2TestRequest("simple test", new HTTPClientRequest("www.google.com", 443, true).get("/"), ConnectMethod.ALPN, 3, new CheckGoogleHomePage()));

		return list;
	}
	
	public static class LogResponse implements ResponseChecker {
		@Override
		public void check(HTTPClientRequest request, HTTPClientResponse response, IOException error) throws Exception {
			System.out.println(response.getHeaders().generateString(1024).asString());
			Assert.assertEquals(200, response.getStatusCode());
			ParameterizedHeaderValue ct = response.getHeaders().getContentType();
			if (response.getEntity() instanceof TextEntity) {
				String text = ((TextEntity)response.getEntity()).getText();
				System.out.println(text);
			} else if (response.getEntity() instanceof BinaryEntity) {
				Charset charset = ct.getParameter("charset") != null ? Charset.forName(ct.getParameter("charset")) : StandardCharsets.ISO_8859_1;
				CharArrayStringBuffer content = IOUtil.readFullyAsString(((BinaryEntity)response.getEntity()).getContent(), charset, Priority.NORMAL).blockResult(0);
				System.out.println(content.asString());
			} else {
				throw new Exception("Unsupported entity type: " + response.getEntity());
			}
		}
	}
	
	public static class CheckWikipediaHomePage implements ResponseChecker {
		@Override
		public void check(HTTPClientRequest request, HTTPClientResponse response, IOException error) throws Exception {
			Assert.assertEquals(200, response.getStatusCode());
			ParameterizedHeaderValue ct = response.getHeaders().getContentType();
			Assert.assertEquals("text/html", ct.getMainValue());
			String text = ((TextEntity)response.getEntity()).getText();
			Assert.assertTrue(text.contains("<title>Wikipedia</title>"));
		}
	}
	
	public static class CheckW3CValidatorHomePage implements ResponseChecker {
		@Override
		public void check(HTTPClientRequest request, HTTPClientResponse response, IOException error) throws Exception {
			Assert.assertEquals(200, response.getStatusCode());
			ParameterizedHeaderValue ct = response.getHeaders().getContentType();
			Assert.assertEquals("text/html", ct.getMainValue());
			String text = ((TextEntity)response.getEntity()).getText();
			Assert.assertTrue(text.contains("<title>The W3C Markup Validation Service</title>"));
		}
	}
	
	public static class CheckGoogleHomePage implements ResponseChecker {
		@Override
		public void check(HTTPClientRequest request, HTTPClientResponse response, IOException error) throws Exception {
			Assert.assertEquals(200, response.getStatusCode());
			ParameterizedHeaderValue ct = response.getHeaders().getContentType();
			Assert.assertEquals("text/html", ct.getMainValue());
			String text = ((TextEntity)response.getEntity()).getText();
			Assert.assertTrue(text.contains("<title>Google</title>"));
		}
	}
	
	public static class CheckGolangReqinfo implements ResponseChecker {
		@Override
		public void check(HTTPClientRequest request, HTTPClientResponse response, IOException error) throws Exception {
			Assert.assertEquals(200, response.getStatusCode());
			ParameterizedHeaderValue ct = response.getHeaders().getContentType();
			Assert.assertEquals("text/plain", ct.getMainValue());
			String text = ((TextEntity)response.getEntity()).getText();
			Assert.assertTrue(text.contains("Protocol: HTTP/2.0"));
		}
	}
	
	public TestHttp2Client(Http2TestRequest test) {
		this.test = test;
	}
	
	private Http2TestRequest test;
	
	@Before
	public void initTest() throws Exception {
		AbstractNetworkTest.deactivateNetworkTraces();
		test.init();
	}
	
	@Test
	public void test() throws Exception {
		//AbstractNetworkTest.activateNetworkTraces();
		HTTPClientConfiguration config = new HTTPClientConfiguration();
		HTTP2Settings settings = new HTTP2Settings();
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(TestHttp2Client.class);
		try (HTTP2Client client = new HTTP2Client(config, settings, logger, ByteArrayCache.getInstance())) {
			IAsync<IOException> connect;
			switch (test.connectMethod) {
			case PRIOR_KNOWLEDGE:
				connect = client.connectWithPriorKnowledge(new InetSocketAddress(test.request.getHostname(), test.request.getPort()), test.request.getHostname(), test.request.isSecure());
				break;
			case UPGRADE:
				connect = client.connectWithUpgrade(new InetSocketAddress(test.request.getHostname(), test.request.getPort()), test.request.getHostname(), test.request.isSecure());
				break;
			case ALPN:
				Assume.assumeTrue(SSLConnectionConfig.ALPN_SUPPORTED);
				connect = client.connectWithALPN(new InetSocketAddress(test.request.getHostname(), test.request.getPort()), test.request.getHostname());
				break;
			default:
				throw new IllegalStateException("Invalid connect method");
			}
			connect.blockThrow(0);
			HTTPClientRequest req = new HTTPClientRequest(test.request);
			HTTPClientRequestContext ctx = new HTTPClientRequestContext(client, req);
			ctx.setMaxRedirections(test.maxRedirection);
			client.send(ctx);
			ctx.getResponse().getTrailersReceived().blockThrow(0);
			test.check(req, ctx.getResponse(), null);
		}
	}
/*	

*/
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
