package net.lecousin.framework.network.http.test.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.net.URI;
import java.util.Collections;
import java.util.List;

import net.lecousin.framework.network.SocketOptionValue;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.interceptors.UserAgentInterceptor;
import net.lecousin.framework.network.http.exception.UnsupportedHTTPProtocolException;
import net.lecousin.framework.network.http.test.AbstractHTTPTest;

import org.junit.Assert;
import org.junit.Test;

public class TestHttpClient extends AbstractHTTPTest {
	
	@Test
	public void testBasics() throws Exception {
		HTTPClientConfiguration config = new HTTPClientConfiguration(HTTPClientConfiguration.defaultConfiguration);
		config.setSocketOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);
		config.setSocketOption(StandardSocketOptions.TCP_NODELAY, Boolean.FALSE);
		config.setSocketOption(StandardSocketOptions.SO_KEEPALIVE, Boolean.TRUE);
		config.setSocketOption(new SocketOptionValue<>(StandardSocketOptions.SO_BROADCAST, Boolean.TRUE));
		config.setSocketOption(new SocketOptionValue<>(StandardSocketOptions.SO_BROADCAST, Boolean.FALSE));
		config.setSocketOption(new SocketOptionValue<>(StandardSocketOptions.SO_RCVBUF, Integer.valueOf(1234)));
		config = new HTTPClientConfiguration(config);
		Assert.assertEquals(Boolean.FALSE, config.getSocketOption(StandardSocketOptions.TCP_NODELAY));
		Assert.assertEquals(Boolean.FALSE, config.getSocketOption(StandardSocketOptions.SO_BROADCAST));
		Assert.assertEquals(Boolean.TRUE, config.getSocketOption(StandardSocketOptions.SO_KEEPALIVE));
		Assert.assertEquals(Integer.valueOf(1234), config.getSocketOption(StandardSocketOptions.SO_RCVBUF));
		Assert.assertNull(config.getSocketOption(StandardSocketOptions.IP_MULTICAST_LOOP));
		config.getSocketOptions();
		config.setSSLContext(sslTest);
		config.insertInterceptorFirst(new UserAgentInterceptor("test", true));
		
		try {
			HTTPClient.create(new URI("ftp://localhost"));
			throw new AssertionError("HTTPClient must reject non-http protocols");
		} catch (UnsupportedHTTPProtocolException e) {}
		try {
			HTTPClient.create(new URI("localhost"));
			throw new AssertionError("HTTPClient must reject non-http protocols");
		} catch (UnsupportedHTTPProtocolException e) {}
		
		HTTPClient client = HTTPClient.create(new URI("http://localhost"));
		Assert.assertEquals(80, client.getRequestedPort());
		Assert.assertEquals("localhost", client.getRequestedHostname());
		Assert.assertNotNull(client.getTCPClient());;
		client = HTTPClient.create(new URI("https://localhost"));
		Assert.assertEquals(443, client.getRequestedPort());
		Assert.assertEquals("localhost", client.getRequestedHostname());
		client = HTTPClient.create(new URI("http://localhost:123"));
		Assert.assertEquals(123, client.getRequestedPort());
		Assert.assertEquals("localhost", client.getRequestedHostname());
		client = HTTPClient.create(new URI("https://localhost:123"), config);
		Assert.assertEquals(123, client.getRequestedPort());
		Assert.assertEquals("localhost", client.getRequestedHostname());
	}
	
	@Test
	public void testSendAndReceive() throws Exception {
		URI uri = new URI(HTTP_BIN + "get");
		try (HTTPClient client = HTTPClient.create(uri)) {
			client.sendAndReceive(new HTTPRequest().get().setURI(uri), null, null).blockResult(0);
		}
	}
	
	@Test
	public void testProxy() throws Exception {
		/*
		Pair<HTTPResponse, IO.Readable.Seekable> p = HTTPClientUtil.sendAndReceiveFully(Method.GET, "https://gimmeproxy.com/api/getProxy", (IO.Readable)null).blockResult(0);
		JSONParser parser = new JSONParser();
		Object o = parser.parse(new InputStreamReader(IOAsInputStream.get(p.getValue2())));
		Assert.assertTrue(o instanceof JSONObject);
		String ip = (String)((JSONObject)o).get("ip");
		String port = (String)((JSONObject)o).get("port");
		
		System.out.println("Test with proxy " + ip + ":" + port);
		*/
		String ip = HTTP_BIN_DOMAIN;
		String port = "80";
		
		HTTPClientConfiguration cfg = new HTTPClientConfiguration(HTTPClientConfiguration.defaultConfiguration);
		cfg.setProxySelector(new ProxySelector() {
			@Override
			public List<Proxy> select(URI uri) {
				return Collections.singletonList(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(ip, Integer.parseInt(port))));
			}
			
			@Override
			public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
			}
		});
		
		HTTPClient client = HTTPClient.create(new URI("http://example.com"), cfg);
		HTTPRequest req = new HTTPRequest().get("/");
		req.addHeader("Host", "example.com");
		client.sendRequest(req).blockThrow(0);
		client.receiveResponse().blockResult(0);
		client.close();
	}
	
}
