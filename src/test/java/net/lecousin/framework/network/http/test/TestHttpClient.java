package net.lecousin.framework.network.http.test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IOAsInputStream;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.out2in.OutputToInput;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPRequest.Method;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientUtil;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.mime.entity.FormDataEntity;
import net.lecousin.framework.network.mime.entity.FormUrlEncodedEntity;
import net.lecousin.framework.network.mime.entity.MimeEntity;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.Triple;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.Assert;
import org.junit.Test;

public class TestHttpClient extends AbstractHTTPTest {
	
	@Test(timeout=120000)
	public void testHttpGetGoogle() throws Exception {
		AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> result = testGet("http://www.google.com/", 3);
		result.blockThrow(0);
		checkGetGoogle(result);
	}

	@Test(timeout=120000)
	public void testHttpsGetGoogle() throws Exception {
		AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> result = testGet("https://www.google.com/", 3);
		result.blockThrow(0);
		checkGetGoogle(result);
	}
	
	@SuppressWarnings("resource")
	private static void checkGetGoogle(AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> result) throws IOException {
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
	
	@Test(timeout=120000)
	public void testHttpBinGet() throws Exception {
		AsyncWork<Triple<HTTPRequest, HTTPResponse, IO.Readable.Seekable>, IOException> get = testGetWithRequest("http://httpbin.org/get", "X-Test", "a test");
		get.blockThrow(0);
		checkHttpBin(get.getResult().getValue1(), get.getResult().getValue2(), get.getResult().getValue3(), "http://httpbin.org/get");
	}
	
	@Test(timeout=120000)
	public void testHttpsBinGet() throws Exception {
		AsyncWork<Triple<HTTPRequest, HTTPResponse, IO.Readable.Seekable>, IOException> get = testGetWithRequest("https://httpbin.org/get", "X-Test", "a test");
		get.blockThrow(0);
		checkHttpBin(get.getResult().getValue1(), get.getResult().getValue2(), get.getResult().getValue3(), "https://httpbin.org/get");
	}
	
	@Test(timeout=120000)
	public void testHttpBinGetGzip() throws Exception {
		AsyncWork<Triple<HTTPRequest, HTTPResponse, IO.Readable.Seekable>, IOException> get = testGetWithRequest("http://httpbin.org/gzip", "X-Test", "a test");
		get.blockThrow(0);
		checkHttpBin(get.getResult().getValue1(), get.getResult().getValue2(), get.getResult().getValue3(), "http://httpbin.org/gzip");
	}
	
	@Test(timeout=120000)
	public void testHttpBinGetBytes() throws Exception {
		AsyncWork<Triple<HTTPRequest, HTTPResponse, IO.Readable.Seekable>, IOException> get = testGetWithRequest("http://httpbin.org/bytes/15000");
		get.blockThrow(0);
		IO.Readable.Seekable data = get.getResult().getValue3();
		byte[] buf = new byte[20000];
		int nb = data.readFullyAsync(ByteBuffer.wrap(buf)).blockResult(0).intValue();
		Assert.assertEquals(15000, nb);
	}
	
	@Test(timeout=120000)
	public void testHttpBinGetBytesChunked() throws Exception {
		AsyncWork<Triple<HTTPRequest, HTTPResponse, IO.Readable.Seekable>, IOException> get = testGetWithRequest("http://httpbin.org/stream-bytes/15000");
		get.blockThrow(0);
		IO.Readable.Seekable data = get.getResult().getValue3();
		byte[] buf = new byte[20000];
		int nb = data.readFullyAsync(ByteBuffer.wrap(buf)).blockResult(0).intValue();
		Assert.assertEquals(15000, nb);
	}
	
	@SuppressWarnings("resource")
	@Test(timeout=120000)
	public void testHttpBinPost() throws Exception {
		FormDataEntity form1 = new FormDataEntity();
		form1.addField("myfield", "valueofmyfield", StandardCharsets.US_ASCII);
		FormUrlEncodedEntity form2 = new FormUrlEncodedEntity();
		form2.add("mykey", "my value");
		form2.add("another key", "a value with a = and spaces");
		FormDataEntity form3 = new FormDataEntity();
		form3.addField("myfield", "valueofmyfield", StandardCharsets.US_ASCII);
		form3.addFile("myfile", "the_filename", "application/octet-stream", new ByteArrayIO(new byte[] { 0, 1, 2, 3, 4, 5}, "the_file"));
		AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> send;

		send = HTTPClientUtil.sendAndReceiveFully(Method.POST, "http://httpbin.org/post", form1);
		send.blockThrow(0);
		JSONParser parser = new JSONParser();
		Object o = parser.parse(new InputStreamReader(IOAsInputStream.get(send.getResult().getValue2())));
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

		send = HTTPClientUtil.sendAndReceiveFully(Method.POST, "http://httpbin.org/post", form2);
		send.blockThrow(0);
		parser = new JSONParser();
		o = parser.parse(new InputStreamReader(IOAsInputStream.get(send.getResult().getValue2())));
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

		send = HTTPClientUtil.sendAndReceiveFully(Method.POST, "http://httpbin.org/post", form3);
		send.blockThrow(0);
		parser = new JSONParser();
		o = parser.parse(new InputStreamReader(IOAsInputStream.get(send.getResult().getValue2())));
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
	
	@Test(timeout=120000)
	public void testSendAndReceiveHeaders() throws Exception {
		Pair<HTTPClient, HTTPResponse> p = HTTPClientUtil.sendAndReceiveHeaders(Method.GET, "http://httpbin.org/get", (IO.Readable)null).blockResult(0);
		p.getValue1().close();
		p = HTTPClientUtil.sendAndReceiveHeaders(Method.GET, "http://httpbin.org/get", (MimeEntity)null).blockResult(0);
		p.getValue1().close();
	}
	
	@Test(timeout=120000)
	public void testSendAndReceive() throws Exception {
		Pair<HTTPResponse, IO.Readable.Seekable> p = HTTPClientUtil.sendAndReceive(Method.GET, "http://httpbin.org/get", (IO.Readable)null, "X-Test", "a test").blockResult(0);
		checkHttpBin(null, p.getValue1(), p.getValue2(), "http://httpbin.org/get");
		p = HTTPClientUtil.sendAndReceive(Method.GET, "http://httpbin.org/get", (MimeEntity)null, "X-Test", "a test").blockResult(0);
		checkHttpBin(null, p.getValue1(), p.getValue2(), "http://httpbin.org/get");
	}
	
	@Test(timeout=120000)
	public void testSendAndReceiveFully() throws Exception {
		Pair<HTTPResponse, IO.Readable.Seekable> p = HTTPClientUtil.sendAndReceiveFully(Method.GET, "http://httpbin.org/get", (IO.Readable)null, "X-Test", "a test").blockResult(0);
		checkHttpBin(null, p.getValue1(), p.getValue2(), "http://httpbin.org/get");
		p = HTTPClientUtil.sendAndReceiveFully(Method.GET, "http://httpbin.org/get", (MimeEntity)null, "X-Test", "a test").blockResult(0);
		checkHttpBin(null, p.getValue1(), p.getValue2(), "http://httpbin.org/get");
	}
	
	@SuppressWarnings("unused")
	private static void checkHttpBin(HTTPRequest request, HTTPResponse response, IO.Readable.Seekable content, String url) throws Exception {
		JSONParser parser = new JSONParser();
		Object o = parser.parse(new InputStreamReader(IOAsInputStream.get(content)));
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
		for (Pair<String, String> header : request.getMIME().getHeadersList())
			System.out.println("  " + header.getValue1() + ": " + header.getValue2());
		System.out.println("Headers received:");
		Map<String, String> received = new HashMap<>();
		for (Object key : json.keySet()) {
			System.out.println("  " + key + ": " + json.get(key));
			received.put(key.toString().toLowerCase(), (String)json.get(key));
		}
		
		for (Pair<String, String> header : request.getMIME().getHeadersList()) {
			String h = header.getValue1();
			if ("content-length".equals(h)) continue;
			if ("host".equals(h)) continue;
			if ("connection".equals(h)) continue;
			Assert.assertEquals("Header sent: " + h, header.getValue2(), received.get(h));
		}
		for (String key : received.keySet())
			Assert.assertTrue("Header received: " + key, request.getMIME().hasHeader(key));
	}
	
	private static AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> testGet(String url, int maxRedirects, String... headers) {
		System.out.println("Requesting HTTP server with GET method: " + url);
		try { return HTTPClientUtil.GETfully(url, maxRedirects, headers); }
		catch (Throwable t) {
			return new AsyncWork<>(null, new IOException("Error", t));
		}
	}
	
	@SuppressWarnings("resource")
	private static AsyncWork<Triple<HTTPRequest, HTTPResponse, IO.Readable.Seekable>, IOException> testGetWithRequest(String url, String... headers) throws Exception {
		AsyncWork<Triple<HTTPRequest, HTTPResponse, IO.Readable.Seekable>, IOException> result = new AsyncWork<>();
		System.out.println("Requesting HTTP server with GET method: " + url);
		URI uri = new URI(url);
		HTTPClient client = HTTPClient.create(uri);
		HTTPRequest request = new HTTPRequest(Method.GET, HTTPClientUtil.getRequestPath(uri));
		for (int i = 0; i < headers.length - 1; i += 2)
			request.getMIME().setHeader(headers[i], headers[i + 1]);
		client.sendRequest(request, null).listenInline(
			() -> {
				client.receiveResponseHeader().listenInline(
					(response) -> {
						if ((response.getStatusCode() / 100) != 2) {
							result.unblockError(new HTTPResponseError(response.getStatusCode(), response.getStatusMessage()));
							client.close();
							return;
						}
						IOInMemoryOrFile io = new IOInMemoryOrFile(1024*1024, Task.PRIORITY_NORMAL, url.toString());
						OutputToInput output = new OutputToInput(io, url.toString());
						client.receiveBody(response, output, 64 * 1024).listenInline(
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
