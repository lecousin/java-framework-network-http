package net.lecousin.framework.network.http.test;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.Assert;
import org.junit.Test;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.IOAsInputStream;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.buffering.MemoryIO;
import net.lecousin.framework.io.out2in.OutputToInput;
import net.lecousin.framework.network.SocketOptionValue;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPRequest.Method;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientUtil;
import net.lecousin.framework.network.http.client.interceptors.UserAgentInterceptor;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http.exception.UnsupportedHTTPProtocolException;
import net.lecousin.framework.network.mime.MimeHeader;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.entity.FormDataEntity;
import net.lecousin.framework.network.mime.entity.FormUrlEncodedEntity;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.Triple;

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
	public void testHttpGetGoogle() throws Exception {
		AsyncSupplier<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> result = testGet(HTTP_GOOGLE, 3);
		result.blockThrow(0);
		checkGetGoogle(result);
	}

	@Test
	public void testHttpsGetGoogle() throws Exception {
		AsyncSupplier<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> result = testGet(HTTPS_GOOGLE, 3);
		result.blockThrow(0);
		checkGetGoogle(result);
	}
	
	@SuppressWarnings("resource")
	private static void checkGetGoogle(AsyncSupplier<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> result) throws IOException {
		if (result.hasError())
			throw result.getError();
		Pair<HTTPResponse, IO.Readable.Seekable> p = result.getResult();
		HTTPResponse resp = p.getValue1();
		IO.Readable.Seekable body = p.getValue2();
		if (resp.getStatusCode() != 200) // redirect to local domain
			throw new AssertionError("Status received from Google: " + resp.getStatusCode());
		// TODO
		if (body.canStartReading().hasError())
			throw body.canStartReading().getError();
	}
	
	@Test
	public void testHttpBinGet() throws Exception {
		AsyncSupplier<Triple<HTTPRequest, HTTPResponse, IO.Readable.Seekable>, IOException> get = testGetWithRequest(HTTP_BIN+"get", new MimeHeader("X-Test", "a test"));
		get.blockThrow(0);
		checkHttpBin(get.getResult().getValue1(), get.getResult().getValue2(), get.getResult().getValue3(), HTTPS_BIN+"get");
		
		HTTPClient client = HTTPClient.create(new URL(HTTP_BIN+"get"));
		HTTPRequest req = new HTTPRequest(Method.GET, "/get");
		req.getMIME().addHeaderRaw("X-Test", "a test");
		System.out.println("Sending request");
		client.sendRequest(req).blockThrow(0);
		System.out.println("Request sent");
		MemoryIO io = new MemoryIO(2048, "test");
		AsyncSupplier<Pair<HTTPResponse, OutputToInput>, IOException> headerListener = new AsyncSupplier<>();
		AsyncSupplier<HTTPResponse,IOException> outputListener = new AsyncSupplier<>();
		client.receiveResponse("test", io, 1024, headerListener, outputListener);
		System.out.println("Waiting for headers");
		headerListener.blockThrow(0);
		System.out.println("Waiting for body");
		outputListener.blockThrow(0);
		System.out.println("Response received");
		checkHttpBin(req, outputListener.getResult(), headerListener.getResult().getValue2(), HTTPS_BIN+"get");
		io.close();
		client.close();

		
		client = HTTPClient.create(new URL(HTTP_BIN+"get"));
		req = new HTTPRequest(Method.GET, "/get");
		req.getMIME().addHeaderRaw("X-Test", "a test");
		System.out.println("Sending request");
		client.sendRequest(req).blockThrow(0);
		System.out.println("Request sent");
		io = new MemoryIO(2048, "test");
		System.out.println("Waiting for response");
		AsyncSupplier<HTTPResponse, IOException> headersListener = new AsyncSupplier<>();
		Async<IOException> result = client.receiveResponse(headersListener, io, 512);
		result.blockThrow(0);
		System.out.println("Response received");
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		checkHttpBin(req, headersListener.getResult(), io, HTTPS_BIN+"get");
		io.close();
		client.close();

		client = HTTPClient.create(new URL(HTTP_BIN+"get"));
		req = new HTTPRequest(Method.GET, "/get");
		req.getMIME().addHeaderRaw("X-Test", "a test");
		System.out.println("Sending request");
		client.sendRequest(req).blockThrow(0);
		System.out.println("Request sent");
		AsyncSupplier<Pair<HTTPResponse, MemoryIO>, IOException> bodyReceived = new AsyncSupplier<>();
		client.receiveResponse(response -> new Pair<>(new MemoryIO(1024, "test"), Integer.valueOf(1024)), bodyReceived);
		System.out.println("Waiting for response");
		Pair<HTTPResponse, MemoryIO> p = bodyReceived.blockResult(0);
		System.out.println("Response received");
		p.getValue2().seekSync(SeekType.FROM_BEGINNING, 0);
		checkHttpBin(req, p.getValue1(), p.getValue2(), HTTPS_BIN+"get");
		io.close();
		client.close();
	}
	
	@Test
	public void testHttpsBinGet() throws Exception {
		AsyncSupplier<Triple<HTTPRequest, HTTPResponse, IO.Readable.Seekable>, IOException> get = testGetWithRequest(HTTPS_BIN+"get", new MimeHeader("X-Test", "a test"));
		get.blockThrow(0);
		checkHttpBin(get.getResult().getValue1(), get.getResult().getValue2(), get.getResult().getValue3(), HTTPS_BIN+"get");
	}
	
	@Test
	public void testHttpBinRedirect() throws Exception {
		File file = File.createTempFile("test", "http");
		file.deleteOnExit();
		Pair<HTTPResponse, FileIO.ReadWrite> p1 = HTTPClientUtil.GET(HTTP_BIN+"redirect/2", file, 3).blockResult(0);
		checkHttpBin(null, p1.getValue1(), p1.getValue2(), HTTPS_BIN+"get");
	}
	
	@Test
	public void testHttpBinGetGzip() throws Exception {
		AsyncSupplier<Triple<HTTPRequest, HTTPResponse, IO.Readable.Seekable>, IOException> get = testGetWithRequest(HTTP_BIN+"gzip", new MimeHeader("X-Test", "a test"));
		get.blockThrow(0);
		checkHttpBin(get.getResult().getValue1(), get.getResult().getValue2(), get.getResult().getValue3(), HTTP_BIN+"gzip");
	}
	
	@Test
	public void testHttpBinGetBytes() throws Exception {
		AsyncSupplier<Triple<HTTPRequest, HTTPResponse, IO.Readable.Seekable>, IOException> get = testGetWithRequest(HTTP_BIN+"bytes/15000");
		get.blockThrow(0);
		IO.Readable.Seekable data = get.getResult().getValue3();
		byte[] buf = new byte[20000];
		int nb = data.readFullyAsync(ByteBuffer.wrap(buf)).blockResult(0).intValue();
		Assert.assertEquals(15000, nb);
		data.close();
	}
	
	@Test
	public void testHttpBinGetBytesChunked() throws Exception {
		AsyncSupplier<Triple<HTTPRequest, HTTPResponse, IO.Readable.Seekable>, IOException> get = testGetWithRequest(HTTP_BIN+"stream-bytes/15000");
		get.blockThrow(0);
		IO.Readable.Seekable data = get.getResult().getValue3();
		byte[] buf = new byte[20000];
		int nb = data.readFullyAsync(ByteBuffer.wrap(buf)).blockResult(0).intValue();
		Assert.assertEquals(15000, nb);
		data.close();
	}
	
	@SuppressWarnings("resource")
	@Test
	public void testHttpBinPost() throws Exception {
		FormDataEntity form1 = new FormDataEntity();
		form1.addField("myfield", "valueofmyfield", StandardCharsets.US_ASCII);
		FormUrlEncodedEntity form2 = new FormUrlEncodedEntity();
		form2.add("mykey", "my value");
		form2.add("another key", "a value with a = and spaces");
		FormDataEntity form3 = new FormDataEntity();
		form3.addField("myfield", "valueofmyfield", StandardCharsets.US_ASCII);
		form3.addFile("myfile", "the_filename", new ParameterizedHeaderValue("application/octet-stream"), new ByteArrayIO(new byte[] { 0, 1, 2, 3, 4, 5}, "the_file"));
		AsyncSupplier<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> send;

		send = HTTPClientUtil.sendAndReceiveFully(Method.POST, HTTP_BIN+"post", form1);
		send.blockThrow(0);
		JSONParser parser = new JSONParser();
		Object o = parser.parse(new InputStreamReader(IOAsInputStream.get(send.getResult().getValue2(), false)));
		Assert.assertTrue(o instanceof JSONObject);
		JSONObject json = (JSONObject)o;
		o = json.get("form");
		Assert.assertTrue(o instanceof JSONObject);
		JSONObject form = (JSONObject)o;
		Assert.assertEquals(1, form.size());
		Assert.assertTrue(form.containsKey("myfield"));
		o = form.get("myfield");
		Assert.assertTrue(o instanceof String);
		Assert.assertEquals("valueofmyfield", o);

		send = HTTPClientUtil.sendAndReceiveFully(Method.POST, HTTP_BIN+"post", form2);
		send.blockThrow(0);
		parser = new JSONParser();
		o = parser.parse(new InputStreamReader(IOAsInputStream.get(send.getResult().getValue2(), false)));
		Assert.assertTrue(o instanceof JSONObject);
		json = (JSONObject)o;
		o = json.get("form");
		Assert.assertTrue(o instanceof JSONObject);
		form = (JSONObject)o;
		Assert.assertEquals(2, form.size());
		Assert.assertTrue(form.containsKey("mykey"));
		o = form.get("mykey");
		Assert.assertTrue(o instanceof String);
		Assert.assertEquals("my value", o);
		Assert.assertTrue(form.containsKey("another key"));
		o = form.get("another key");
		Assert.assertTrue(o instanceof String);
		Assert.assertEquals("a value with a = and spaces", o);

		send = HTTPClientUtil.sendAndReceiveFully(Method.POST, HTTP_BIN+"post", form3);
		send.blockThrow(0);
		parser = new JSONParser();
		o = parser.parse(new InputStreamReader(IOAsInputStream.get(send.getResult().getValue2(), false)));
		Assert.assertTrue(o instanceof JSONObject);
		json = (JSONObject)o;
		o = json.get("form");
		Assert.assertTrue(o instanceof JSONObject);
		form = (JSONObject)o;
		Assert.assertEquals(1, form.size());
		Assert.assertTrue(form.containsKey("myfield"));
		o = form.get("myfield");
		Assert.assertTrue(o instanceof String);
		Assert.assertEquals("valueofmyfield", o);
		o = json.get("files");
		Assert.assertTrue(o instanceof JSONObject);
		JSONObject files = (JSONObject)o;
		Assert.assertEquals(1, files.size());
		Assert.assertTrue(files.containsKey("myfile"));
	}
	
	@Test
	public void testSendAndReceiveHeaders() throws Exception {
		Pair<HTTPClient, HTTPResponse> p = HTTPClientUtil.sendAndReceiveHeaders(Method.GET, HTTP_BIN+"get", (IO.Readable)null).blockResult(0);
		p.getValue1().close();
		p = HTTPClientUtil.sendAndReceiveHeaders(Method.GET, HTTP_BIN+"get", new MimeMessage()).blockResult(0);
		p.getValue1().close();
	}
	
	@Test
	public void testSendAndReceive() throws Exception {
		Pair<HTTPResponse, IO.Readable.Seekable> p = HTTPClientUtil.sendAndReceive(Method.GET, HTTP_BIN+"get", (IO.Readable)null, new MimeHeader("X-Test", "a test")).blockResult(0);
		checkHttpBin(null, p.getValue1(), p.getValue2(), HTTPS_BIN+"get");
		p = HTTPClientUtil.sendAndReceive(Method.GET, HTTP_BIN+"get", new MimeMessage(new MimeHeader("X-Test", "a test"))).blockResult(0);
		checkHttpBin(null, p.getValue1(), p.getValue2(), HTTPS_BIN+"get");
	}
	
	@Test
	public void testSendAndReceiveFully() throws Exception {
		Pair<HTTPResponse, IO.Readable.Seekable> p = HTTPClientUtil.sendAndReceiveFully(Method.GET, HTTP_BIN+"get", (IO.Readable)null, new MimeHeader("X-Test", "a test")).blockResult(0);
		checkHttpBin(null, p.getValue1(), p.getValue2(), HTTPS_BIN+"get");
		p = HTTPClientUtil.sendAndReceiveFully(Method.GET, HTTP_BIN+"get", new MimeMessage(new MimeHeader("X-Test", "a test"))).blockResult(0);
		checkHttpBin(null, p.getValue1(), p.getValue2(), HTTPS_BIN+"get");
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
		HTTPRequest req = new HTTPRequest(Method.GET, "/");
		req.getMIME().setHeaderRaw("Host", "example.com");
		client.sendRequest(req).blockThrow(0);
		client.receiveResponseHeader().blockResult(0);
		client.close();
	}
	
	@SuppressWarnings("unused")
	private static void checkHttpBin(HTTPRequest request, HTTPResponse response, IO.Readable.Seekable content, String url) throws Exception {
		JSONParser parser = new JSONParser();
		Object o = parser.parse(new InputStreamReader(IOAsInputStream.get(content, false)));
		Assert.assertTrue(o instanceof JSONObject);
		JSONObject json = (JSONObject)o;
		if (json.containsKey("url"))
			Assert.assertEquals(url, json.get("url"));
		o = json.get("headers");
		Assert.assertTrue(o instanceof JSONObject);
		if (request != null)
			checkHttpBinRequestHeaders(request, (JSONObject)o);
	}
	
	private static void checkHttpBinRequestHeaders(HTTPRequest request, JSONObject json) {
		System.out.println("Headers sent:");
		for (MimeHeader header : request.getMIME().getHeaders())
			System.out.println("  " + header.getName() + ": " + header.getRawValue());
		System.out.println("Headers received:");
		Map<String, String> received = new HashMap<>();
		for (Object key : json.keySet()) {
			System.out.println("  " + key + ": " + json.get(key));
			received.put(key.toString().toLowerCase(), (String)json.get(key));
		}
		
		for (MimeHeader header : request.getMIME().getHeaders()) {
			String h = header.getNameLowerCase();
			if ("content-length".equals(h)) continue;
			if ("host".equals(h)) continue;
			if ("connection".equals(h)) continue;
			Assert.assertEquals("Header sent: " + h, header.getRawValue(), received.get(h));
		}
		for (String key : received.keySet())
			Assert.assertTrue("Header received: " + key, request.getMIME().hasHeader(key));
	}
	
	private static AsyncSupplier<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> testGet(String url, int maxRedirects, MimeHeader... headers) {
		System.out.println("Requesting HTTP server with GET method: " + url);
		try { return HTTPClientUtil.GETfully(url, maxRedirects, headers); }
		catch (Throwable t) {
			return new AsyncSupplier<>(null, new IOException("Error", t));
		}
	}
	
	@SuppressWarnings("resource")
	private static AsyncSupplier<Triple<HTTPRequest, HTTPResponse, IO.Readable.Seekable>, IOException> testGetWithRequest(String url, MimeHeader... headers) throws Exception {
		AsyncSupplier<Triple<HTTPRequest, HTTPResponse, IO.Readable.Seekable>, IOException> result = new AsyncSupplier<>();
		System.out.println("Requesting HTTP server with GET method: " + url);
		URI uri = new URI(url);
		HTTPClient client = HTTPClient.create(uri);
		HTTPRequest request = new HTTPRequest(Method.GET, HTTPClientUtil.getRequestPath(uri));
		for (int i = 0; i < headers.length; ++i)
			request.getMIME().addHeader(headers[i]);
		client.sendRequest(request).onDone(
			() -> {
				client.receiveResponseHeader().onDone(
					(response) -> {
						if ((response.getStatusCode() / 100) != 2) {
							result.unblockError(new HTTPResponseError(response.getStatusCode(), response.getStatusMessage()));
							client.close();
							return;
						}
						IOInMemoryOrFile io = new IOInMemoryOrFile(1024*1024, Task.PRIORITY_NORMAL, url.toString());
						OutputToInput output = new OutputToInput(io, url.toString());
						client.receiveBody(response, output, 64 * 1024).onDone(
							() -> {
								output.endOfData();
								result.unblockSuccess(new Triple<>(request, response, output));
								client.close();
							},
							(error) -> {
								result.error(error);
								client.close();
							},
							(cancel) -> {
								result.cancel(cancel);
								client.close();
							}
						);
					},
					result
				);
			},
			result
		);
		return result;
	}
	
}
