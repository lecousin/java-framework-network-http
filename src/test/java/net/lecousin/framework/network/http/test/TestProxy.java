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

import javax.net.ssl.SSLContext;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.io.buffering.MemoryIO;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPRequest.Method;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http.server.HTTPRequestFilter;
import net.lecousin.framework.network.http.server.HTTPServerProtocol;
import net.lecousin.framework.network.http.server.processor.ProxyHTTPRequestProcessor;
import net.lecousin.framework.network.http.server.processor.ProxyHTTPRequestProcessor.Filter;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.server.TCPServerClient;

public class TestProxy extends AbstractHTTPTest {
	
	private TCPServer server;
	private ProxyHTTPRequestProcessor processor;
	private int serverPort;
	
	@Before
	public void startProxy() throws Exception {
		server = new TCPServer();
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger("test-proxy");
		logger.setLevel(Level.TRACE);
		processor = new ProxyHTTPRequestProcessor(8192, logger);
		HTTPServerProtocol protocol = new HTTPServerProtocol(processor);
		server.setProtocol(protocol);
		SocketAddress serverAddress = server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100);
		serverPort = ((InetSocketAddress)serverAddress).getPort();
		HTTPClientConfiguration config = new HTTPClientConfiguration(HTTPClientConfiguration.defaultConfiguration);
		config.setSSLContext(SSLContext.getDefault());
		processor.setHTTPForwardClientConfiguration(config);
	}
	
	@After
	public void stopProxy() {
		server.close();
	}

	@Test(timeout=60000)
	public void testHttp() throws Exception {
		TCPClient client = new TCPClient();
		HTTPClient http = new HTTPClient(client, "localhost", serverPort, HTTPClientConfiguration.defaultConfiguration);
		HTTPRequest request = new HTTPRequest(Method.GET, HTTP_BIN + "get");
		http.sendRequest(request).blockThrow(0);
		HTTPResponse response = http.receiveResponseHeader().blockResult(0);
		Assert.assertEquals(200, response.getStatusCode());
		MemoryIO io = new MemoryIO(4096, "test");
		http.receiveBody(response, io, 8192).blockThrow(0);
		http.close();
	}

	@Test(timeout=60000)
	public void testHttpsNotAllowed() throws Exception {
		processor.allowForwardFromHttpToHttps(false);
		TCPClient client = new TCPClient();
		HTTPClient http = new HTTPClient(client, "localhost", serverPort, HTTPClientConfiguration.defaultConfiguration);
		HTTPRequest request = new HTTPRequest(Method.GET, HTTPS_BIN + "get");
		http.sendRequest(request).blockThrow(0);
		HTTPResponse response = http.receiveResponseHeader().blockResult(0);
		Assert.assertEquals(404, response.getStatusCode());
		MemoryIO io = new MemoryIO(4096, "test");
		http.receiveBody(response, io, 8192).blockThrow(0);
		http.close();
	}

	@Test(timeout=60000)
	public void testHttpsAllowed() throws Exception {
		processor.allowForwardFromHttpToHttps(true);
		TCPClient client = new TCPClient();
		HTTPClient http = new HTTPClient(client, "localhost", serverPort, HTTPClientConfiguration.defaultConfiguration);
		HTTPRequest request = new HTTPRequest(Method.GET, HTTPS_BIN + "get");
		http.sendRequest(request).blockThrow(0);
		HTTPResponse response = http.receiveResponseHeader().blockResult(0);
		Assert.assertEquals(200, response.getStatusCode());
		MemoryIO io = new MemoryIO(4096, "test");
		http.receiveBody(response, io, 8192).blockThrow(0);
		http.close();
	}

	@Test(timeout=60000)
	public void testInvalidProtocol() throws Exception {
		processor.allowForwardFromHttpToHttps(true);
		TCPClient client = new TCPClient();
		HTTPClient http = new HTTPClient(client, "localhost", serverPort, HTTPClientConfiguration.defaultConfiguration);
		HTTPRequest request = new HTTPRequest(Method.GET, "ftp://google.com");
		http.sendRequest(request).blockThrow(0);
		HTTPResponse response = http.receiveResponseHeader().blockResult(0);
		Assert.assertEquals(404, response.getStatusCode());
		MemoryIO io = new MemoryIO(4096, "test");
		http.receiveBody(response, io, 8192).blockThrow(0);
		http.close();
	}

	@Test(timeout=60000)
	public void testInvalidURL() throws Exception {
		processor.allowForwardFromHttpToHttps(true);
		LCCore.getApplication().getLoggerFactory().getLogger("test-proxy").setLevel(Level.INFO);
		TCPClient client = new TCPClient();
		HTTPClient http = new HTTPClient(client, "localhost", serverPort, HTTPClientConfiguration.defaultConfiguration);
		HTTPRequest request = new HTTPRequest(Method.GET, "hello");
		http.sendRequest(request).blockThrow(0);
		HTTPResponse response = http.receiveResponseHeader().blockResult(0);
		Assert.assertEquals(404, response.getStatusCode());
		MemoryIO io = new MemoryIO(4096, "test");
		http.receiveBody(response, io, 8192).blockThrow(0);
		http.close();
	}

	@Test(timeout=60000)
	public void testEmptyURL() throws Exception {
		processor.allowForwardFromHttpToHttps(true);
		TCPClient client = new TCPClient();
		HTTPClient http = new HTTPClient(client, "localhost", serverPort, HTTPClientConfiguration.defaultConfiguration);
		HTTPRequest request = new HTTPRequest(Method.GET, "");
		http.sendRequest(request).blockThrow(0);
		HTTPResponse response = http.receiveResponseHeader().blockResult(0);
		Assert.assertEquals(400, response.getStatusCode());
		MemoryIO io = new MemoryIO(4096, "test");
		http.receiveBody(response, io, 8192).blockThrow(0);
		http.close();
	}
	
	@Test(timeout=60000)
	public void testHttpClientThroughProxy() throws Exception {
		HTTPClientConfiguration config = new HTTPClientConfiguration(HTTPClientConfiguration.defaultConfiguration);
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
		HTTPClient http = HTTPClient.create(new URI(HTTPS_BIN), config);
		HTTPRequest request = new HTTPRequest(Method.GET, HTTPS_BIN + "get");
		http.sendRequest(request).blockThrow(0);
		HTTPResponse response = http.receiveResponseHeader().blockResult(0);
		Assert.assertEquals(200, response.getStatusCode());
		MemoryIO io = new MemoryIO(4096, "test");
		http.receiveBody(response, io, 8192).blockThrow(0);
		http.close();
	}
	
	@Test(timeout=60000)
	public void testHttpClientThroughProxyNotAllowed() throws Exception {
		HTTPClientConfiguration config = new HTTPClientConfiguration(HTTPClientConfiguration.defaultConfiguration);
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
		HTTPClient http = HTTPClient.create(new URI(HTTPS_BIN), config);
		HTTPRequest request = new HTTPRequest(Method.GET, HTTPS_BIN + "get");
		try {
			http.sendRequest(request).blockThrow(0);
			throw new AssertionError("Request must be rejected");
		} catch (HTTPResponseError e) {
			Assert.assertEquals(405, e.getStatusCode());
		}
		http.close();
	}
	
	@Test(timeout=60000)
	public void testLocalPathMapping() throws Exception {
		processor.mapLocalPath("/titi", HTTP_BIN_DOMAIN, 80, "/get", false);
		processor.mapLocalPath("/tutu", HTTP_BIN_DOMAIN, 443, "/get", true);
		processor.addFilter(new HTTPRequestFilter() {
			@Override
			public ISynchronizationPoint<?> filter(TCPServerClient client, HTTPRequest request, HTTPResponse response) {
				request.getMIME().addHeaderRaw("X-Test", "test");
				return null;
			}
		});
		processor.addFilter(new Filter() {
			@Override
			public ISynchronizationPoint<Exception> filter(HTTPRequest request, HTTPResponse response, String hostname, int port) {
				return null;
			}
		});
		
		TCPClient client = new TCPClient();
		HTTPClient http = new HTTPClient(client, "localhost", serverPort, HTTPClientConfiguration.defaultConfiguration);
		HTTPRequest request = new HTTPRequest(Method.GET, "/titi");
		http.sendRequest(request).blockThrow(0);
		HTTPResponse response = http.receiveResponseHeader().blockResult(0);
		Assert.assertEquals(200, response.getStatusCode());
		MemoryIO io = new MemoryIO(4096, "test");
		http.receiveBody(response, io, 8192).blockThrow(0);
		http.close();
		Assert.assertEquals("application/json", response.getMIME().getFirstHeader("Content-Type").getRawValue());
		
		client = new TCPClient();
		http = new HTTPClient(client, "localhost", serverPort, HTTPClientConfiguration.defaultConfiguration);
		request = new HTTPRequest(Method.GET, "/tutu");
		http.sendRequest(request).blockThrow(0);
		response = http.receiveResponseHeader().blockResult(0);
		Assert.assertEquals(200, response.getStatusCode());
		io = new MemoryIO(4096, "test");
		http.receiveBody(response, io, 8192).blockThrow(0);
		http.close();
		Assert.assertEquals("application/json", response.getMIME().getFirstHeader("Content-Type").getRawValue());
		
		client = new TCPClient();
		http = new HTTPClient(client, "localhost", serverPort, HTTPClientConfiguration.defaultConfiguration);
		request = new HTTPRequest(Method.GET, "/toto");
		http.sendRequest(request).blockThrow(0);
		response = http.receiveResponseHeader().blockResult(0);
		Assert.assertEquals(404, response.getStatusCode());
		io = new MemoryIO(4096, "test");
		http.receiveBody(response, io, 8192).blockThrow(0);
		http.close();
	}
	
}
