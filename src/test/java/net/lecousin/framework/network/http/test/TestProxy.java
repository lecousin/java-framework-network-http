package net.lecousin.framework.network.http.test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientResponse;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.http.server.HTTPRequestFilter;
import net.lecousin.framework.network.http.server.processor.ProxyHTTPRequestProcessor;
import net.lecousin.framework.network.http.server.processor.ProxyHTTPRequestProcessor.Filter;
import net.lecousin.framework.network.http.test.requests.HttpBin;
import net.lecousin.framework.network.http1.client.HTTP1ClientConnection;
import net.lecousin.framework.network.http1.server.HTTP1ServerProtocol;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.server.TCPServer;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestProxy extends LCCoreAbstractTest {
	
	private TCPServer server;
	private ProxyHTTPRequestProcessor processor;
	private int serverPort;
	
	@Before
	public void startProxy() throws Exception {
		server = new TCPServer();
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger("test-proxy");
		logger.setLevel(Level.TRACE);
		processor = new ProxyHTTPRequestProcessor(HTTPClient.getDefault(), 8192, 10000, 10000, logger);
		HTTP1ServerProtocol protocol = new HTTP1ServerProtocol(processor);
		server.setProtocol(protocol);
		SocketAddress serverAddress = server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100).blockResult(0);
		serverPort = ((InetSocketAddress)serverAddress).getPort();
	}
	
	@After
	public void stopProxy() {
		server.close();
	}

	@Test
	public void testHttp() throws Exception {
		HTTPClientRequest request = new HTTPClientRequest("localhost", serverPort, false).get("http://" + HttpBin.HTTP_BIN_DOMAIN + "/get");
		HTTPClientResponse response = HTTP1ClientConnection.send(request, 0, BinaryEntity::new, new HTTPClientConfiguration());
		response.getBodyReceived().blockThrow(0);
		Assert.assertEquals(200, response.getStatusCode());
		new HttpBin.CheckJSONResponse().check(request, response, null);
	}

	@Test
	public void testHttpsNotAllowed() throws Exception {
		processor.allowForwardFromHttpToHttps(false);
		HTTPClientRequest request = new HTTPClientRequest("localhost", serverPort, false).get("https://" + HttpBin.HTTP_BIN_DOMAIN + "/get");
		HTTPClientResponse response = HTTP1ClientConnection.send(request, 0, BinaryEntity::new, new HTTPClientConfiguration());
		response.getBodyReceived().blockThrow(0);
		Assert.assertEquals(404, response.getStatusCode());
	}

	@Test
	public void testHttpsAllowed() throws Exception {
		processor.allowForwardFromHttpToHttps(true);
		HTTPClientRequest request = new HTTPClientRequest("localhost", serverPort, false).get("https://" + HttpBin.HTTP_BIN_DOMAIN + "/get");
		HTTPClientResponse response = HTTP1ClientConnection.send(request, 0, BinaryEntity::new, new HTTPClientConfiguration());
		response.getBodyReceived().blockThrow(0);
		Assert.assertEquals(200, response.getStatusCode());
		new HttpBin.CheckJSONResponse().check(request, response, null);
	}

	@Test
	public void testInvalidProtocol() throws Exception {
		processor.allowForwardFromHttpToHttps(true);
		HTTPClientRequest request = new HTTPClientRequest("localhost", serverPort, false).get("ftp://google.com");
		HTTPClientResponse response = HTTP1ClientConnection.send(request, 0, BinaryEntity::new, new HTTPClientConfiguration());
		response.getBodyReceived().blockThrow(0);
		Assert.assertEquals(404, response.getStatusCode());
	}

	@Test
	public void testInvalidURL() throws Exception {
		processor.allowForwardFromHttpToHttps(true);
		LCCore.getApplication().getLoggerFactory().getLogger("test-proxy").setLevel(Level.INFO);
		HTTPClientRequest request = new HTTPClientRequest("localhost", serverPort, false).get("hello");
		HTTPClientResponse response = HTTP1ClientConnection.send(request, 0, BinaryEntity::new, new HTTPClientConfiguration());
		response.getBodyReceived().blockThrow(0);
		Assert.assertEquals(404, response.getStatusCode());
	}

	@Test
	public void testEmptyURL() throws Exception {
		processor.allowForwardFromHttpToHttps(true);
		HTTPClientRequest request = new HTTPClientRequest("localhost", serverPort, false).get("");
		HTTPClientResponse response = HTTP1ClientConnection.send(request, 0, BinaryEntity::new, new HTTPClientConfiguration());
		response.getBodyReceived().blockThrow(0);
		Assert.assertEquals(400, response.getStatusCode());
	}
	
	@Test
	public void testHttpClientThroughProxy() throws Exception {
		HTTPClientConfiguration config = new HTTPClientConfiguration();
		config.setProxySelector(new ProxySelector() {
			private Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", serverPort));
			@Override
			public List<Proxy> select(URI uri) {
				return Collections.singletonList(proxy);
			}
			
			@Override
			public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
			}
		});
		processor.allowForwardFromHttpToHttps(true);
		HTTPClientRequest request = new HTTPClientRequest("localhost", serverPort, false).get("https://" + HttpBin.HTTP_BIN_DOMAIN + "/get");
		HTTPClientResponse response = HTTP1ClientConnection.send(request, 0, BinaryEntity::new, new HTTPClientConfiguration());
		response.getBodyReceived().blockThrow(0);
		Assert.assertEquals(200, response.getStatusCode());
	}
	
	@Test
	public void testHttpClientThroughProxyNotAllowed() throws Exception {
		HTTPClientConfiguration config = new HTTPClientConfiguration();
		config.setProxySelector(new ProxySelector() {
			private Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", serverPort));
			@Override
			public List<Proxy> select(URI uri) {
				return Collections.singletonList(proxy);
			}
			
			@Override
			public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
			}
		});
		processor.allowConnectMethod(false);
		HTTPClientRequest request = new HTTPClientRequest("localhost", serverPort, false).get("https://" + HttpBin.HTTP_BIN_DOMAIN + "/get");
		HTTPClientResponse response = HTTP1ClientConnection.send(request, 0, BinaryEntity::new, new HTTPClientConfiguration());
		response.getBodyReceived().blockThrow(0);
		Assert.assertEquals(404, response.getStatusCode());
	}
	
	@Test
	public void testLocalPathMapping() throws Exception {
		processor.mapLocalPath("/titi", HttpBin.HTTP_BIN_DOMAIN, 80, "/get", false);
		processor.mapLocalPath("/tutu", HttpBin.HTTP_BIN_DOMAIN, 443, "/get", true);
		processor.addFilter(new HTTPRequestFilter() {
			@Override
			public void filter(HTTPRequestContext ctx) {
				ctx.getRequest().addHeader("X-Test", "test");
			}
		});
		processor.addFilter(new Filter() {
			@Override
			public void filter(HTTPRequestContext ctx, String hostname, int port) {
			}
		});

		HTTPClientRequest request = new HTTPClientRequest("localhost", serverPort, false).get("/titi");
		HTTPClientResponse response = HTTP1ClientConnection.send(request, 0, BinaryEntity::new, new HTTPClientConfiguration());
		response.getBodyReceived().blockThrow(0);
		Assert.assertEquals(200, response.getStatusCode());
		Assert.assertEquals("application/json", response.getHeaders().getFirst("Content-Type").getRawValue());

		request = new HTTPClientRequest("localhost", serverPort, false).get("/tutu");
		response = HTTP1ClientConnection.send(request, 0, BinaryEntity::new, new HTTPClientConfiguration());
		response.getBodyReceived().blockThrow(0);
		Assert.assertEquals(200, response.getStatusCode());
		Assert.assertEquals("application/json", response.getHeaders().getFirst("Content-Type").getRawValue());
		
		request = new HTTPClientRequest("localhost", serverPort, false).get("/toto");
		response = HTTP1ClientConnection.send(request, 0, BinaryEntity::new, new HTTPClientConfiguration());
		response.getBodyReceived().blockThrow(0);
		Assert.assertEquals(404, response.getStatusCode());
	}
	
}
