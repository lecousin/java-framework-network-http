package net.lecousin.framework.network.http.test.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.Threading;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IOAsInputStream;
import net.lecousin.framework.io.IOFromInputStream;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPRequest.Method;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.mime.MimeHeader;
import net.lecousin.framework.network.mime.entity.FormDataEntity;
import net.lecousin.framework.network.mime.entity.FormDataEntity.PartFile;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.util.Pair;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runners.Parameterized.Parameters;

public class TestHttpClientToHttpBin extends AbstractTestHttpClient {
	
	@Parameters(name = "case = {0}, base url = {1}")
	public static Collection<Object[]> parameters() {
		return parameters(HTTP_BIN, HTTPS_BIN);
	}
	
	public static class CheckJSONResponse implements ResponseChecker {
		
		public CheckJSONResponse() {
		}
		
		public CheckJSONResponse(FormDataEntity formData) {
			this.formData = formData;
		}
		
		private FormDataEntity formData;
		
		@Override
		public void check(HTTPRequest request, HTTPResponse response, IOException error) throws Exception {
			if (error != null)
				throw error;
			
			JSONParser parser = new JSONParser();
			Object o = parser.parse(new InputStreamReader(IOAsInputStream.get(response.getMIME().getBodyReceivedAsInput(), false)));
			Assert.assertTrue(o instanceof JSONObject);
			JSONObject json = (JSONObject)o;
			checkURL(json, request);
			checkHeaders(json, request);
			if (formData != null)
				checkFormData(json, formData);
		}
		
		private static void checkURL(JSONObject json, HTTPRequest request) throws Exception {
			if (json.containsKey("url")) {
				String u = (String)json.get("url");
				URI uri = new URI(u);
				URI expected = new URI(request.getPath());
				Assert.assertEquals(expected.getPath(), uri.getPath());
			}
		}
		
		private static void checkHeaders(JSONObject json, HTTPRequest request) {
			if (!json.containsKey("headers"))
				return;

			JSONObject headers = (JSONObject)json.get("headers");

			Map<String, String> received = new HashMap<>();
			for (Object key : headers.keySet())
				received.put(key.toString().toLowerCase(), (String)headers.get(key));
			
			for (MimeHeader header : request.getMIME().getHeaders()) {
				String h = header.getNameLowerCase();
				if ("content-length".equals(h)) continue;
				if ("host".equals(h)) continue;
				if ("connection".equals(h)) continue;
				if ("transfer-encoding".equals(h)) continue;
				Assert.assertEquals("Header sent: " + h, header.getRawValue(), received.get(h));
			}
		}
		
		private static void checkFormData(JSONObject json, FormDataEntity form) {
			List<Pair<String, String>> expectedFields = form.getFields();
			if (!expectedFields.isEmpty()) {
				Assert.assertTrue(json.containsKey("form"));
				JSONObject jsonForm = (JSONObject)json.get("form");
				Assert.assertEquals(expectedFields.size(), jsonForm.size());
				for (Pair<String, String> expectedField : expectedFields) {
					Assert.assertTrue(jsonForm.containsKey(expectedField.getValue1()));
					Object o = jsonForm.get(expectedField.getValue1());
					Assert.assertTrue(o instanceof String);
					Assert.assertEquals(expectedField.getValue2(), o);
				}
			}

			List<PartFile> expectedFiles = form.getPartsOfType(FormDataEntity.PartFile.class);
			if (!expectedFiles.isEmpty()) {
				Assert.assertTrue(json.containsKey("files"));
				JSONObject jsonFiles = (JSONObject)json.get("files");
				Assert.assertEquals(expectedFiles.size(), jsonFiles.size());
				for (PartFile expectedFile : expectedFiles) {
					Assert.assertTrue(jsonFiles.containsKey(expectedFile.getName()));
				}
			}
		}
		
	}
	
	private static class CheckError implements ResponseChecker {
		
		private CheckError(int expectedCode) {
			this.expectedCode = expectedCode;
		}
		
		private int expectedCode;
		
		@Override
		public void check(HTTPRequest request, HTTPResponse response, IOException error) throws Exception {
			if (error == null)
				throw new AssertionError("No error returned, expected was status code " + expectedCode);
			Assert.assertTrue(error instanceof HTTPResponseError);
			Assert.assertEquals(expectedCode, ((HTTPResponseError)error).getStatusCode());
		}
		
	}
	
	private static class CheckDataResponse implements ResponseChecker {
		
		private CheckDataResponse(int expectedSize) {
			this.expectedSize = expectedSize;
		}
		
		private int expectedSize;
		
		@Override
		public void check(HTTPRequest request, HTTPResponse response, IOException error) throws Exception {
			if (error != null)
				throw error;
			
			IO.Readable data = response.getMIME().getBodyReceivedAsInput();
			byte[] buf = new byte[expectedSize + 16384];
			int nb = data.readFullyAsync(ByteBuffer.wrap(buf)).blockResult(0).intValue();
			Assert.assertEquals(expectedSize, nb);
			data.close();
		}
	}

	@Test
	public void testGet() throws Exception {
		testRequest("get", new HTTPRequest(Method.GET).setHeaders(new MimeHeader("X-Test", "a test")), 0, new CheckJSONResponse());
	}

	@Test
	public void testGzip() throws Exception {
		testRequest("gzip", new HTTPRequest(Method.GET).setHeaders(new MimeHeader("X-Test", "a test")), 0, new CheckJSONResponse());
	}

	@Test
	public void testRedirect1On3() throws Exception {
		testRequest("redirect/1", new HTTPRequest(Method.GET).setHeaders(new MimeHeader("X-Test", "a test")), 3, new CheckJSONResponse());
	}

	@Test
	public void testRedirect2On3() throws Exception {
		testRequest("redirect/2", new HTTPRequest(Method.GET).setHeaders(new MimeHeader("X-Test", "a test")), 3, new CheckJSONResponse());
	}

	@Test
	public void testRedirect3On3() throws Exception {
		testRequest("redirect/3", new HTTPRequest(Method.GET).setHeaders(new MimeHeader("X-Test", "a test")), 3, new CheckJSONResponse());
	}

	@Test
	public void testRedirect4On3() throws Exception {
		testRequest("redirect/4", new HTTPRequest(Method.GET).setHeaders(new MimeHeader("X-Test", "a test")), 3, new CheckError(302));
	}

	@Test
	public void testGetBytes() throws Exception {
		testRequest("bytes/15000", new HTTPRequest(Method.GET), 0, new CheckDataResponse(15000));
	}

	@Test
	public void testGetBytesChunked() throws Exception {
		testRequest("stream-bytes/15000", new HTTPRequest(Method.GET), 0, new CheckDataResponse(15000));
	}
	
	@Test
	public void testDownload() throws Exception {
		URI uri = new URI(HTTP_BIN + "stream-bytes/51478");
		File file = File.createTempFile("test", "download");
		try (HTTPClient client = HTTPClient.create(uri)) {
			Pair<HTTPResponse, FileIO.ReadWrite> p = client.download(new HTTPRequest(Method.GET).setURI(uri), file, 0).blockResult(0);
			Assert.assertEquals(200, p.getValue1().getStatusCode());
			p.getValue2().close();
			Assert.assertEquals(51478, file.length());
		} finally {
			file.delete();
		}
	}
	
	@Test
	public void testPostFormDataWith1Field() throws Exception {
		FormDataEntity form = new FormDataEntity();
		form.addField("myfield", "valueofmyfield", StandardCharsets.US_ASCII);

		testRequest("post", new HTTPRequest().post(form).setHeaders(new MimeHeader("X-Test", "a test")), 0, new CheckJSONResponse(form));
	}
	
	@Test
	public void testPostFormDataWith1FieldAndUnknownSize() throws Exception {
		FormDataEntity form = new FormDataEntity();
		form.addField("myfield", "valueofmyfield", StandardCharsets.US_ASCII);

		HTTPRequest request = new HTTPRequest(Method.POST).setHeaders(new MimeHeader("X-Test", "a test"));
		for (MimeHeader h : form.getHeaders())
			request.getMIME().addHeader(h);
		InputStream is = IOAsInputStream.get(form.getBodyToSend(), false);
		IO.Readable io = new IOFromInputStream(is, "test form", Threading.getCPUTaskManager(), Task.PRIORITY_NORMAL);
		request.getMIME().setBodyToSend(io);
		testRequest("post", request, 0, new CheckJSONResponse(form));
	}
	
	@Test
	public void testPostFormDataWith3Fields() throws Exception {
		FormDataEntity form = new FormDataEntity();
		form.addField("myfield", "valueofmyfield", StandardCharsets.US_ASCII);
		form.addField("toto", "titi", StandardCharsets.US_ASCII);
		form.addField("hello", "world", StandardCharsets.US_ASCII);

		testRequest("post", new HTTPRequest().post(form).setHeaders(new MimeHeader("X-Test", "a test")), 0, new CheckJSONResponse(form));
	}
	
	@Test
	public void testPostFormDataWith1File() throws Exception {
		FormDataEntity form = new FormDataEntity();
		form.addFile("myfile", "the_filename", new ParameterizedHeaderValue("application/octet-stream"), new ByteArrayIO(new byte[] { 0, 1, 2, 3, 4, 5}, "the_file"));

		testRequest("post", new HTTPRequest().post(form).setHeaders(new MimeHeader("X-Test", "a test")), 0, new CheckJSONResponse(form));
	}
	
	@Test
	public void testPostFormDataWith1FileAnd1Field() throws Exception {
		FormDataEntity form = new FormDataEntity();
		form.addFile("myfile", "the_filename", new ParameterizedHeaderValue("application/octet-stream"), new ByteArrayIO(new byte[] { 0, 1, 2, 3, 4, 5}, "the_file"));
		form.addField("myfield", "valueofmyfield", StandardCharsets.US_ASCII);

		testRequest("post", new HTTPRequest().post(form).setHeaders(new MimeHeader("X-Test", "a test")), 0, new CheckJSONResponse(form));
	}
	
	@Test
	public void testPostFormDataWith3FilesAnd2Fields() throws Exception {
		FormDataEntity form = new FormDataEntity();
		form.addFile("myfile", "the_filename", new ParameterizedHeaderValue("application/octet-stream"), new ByteArrayIO(new byte[] { 0, 1, 2, 3, 4, 5}, "the_file"));
		form.addField("myfield", "valueofmyfield", StandardCharsets.US_ASCII);
		form.addField("myfield2", "valueofmyfield2", StandardCharsets.US_ASCII);
		form.addFile("f2", "second.bin", new ParameterizedHeaderValue("application/octet-stream"), new ByteArrayIO(new byte[] { 0, 1, 2, 3, 4, 5}, "the_file"));
		form.addFile("f3", "third.bin", new ParameterizedHeaderValue("application/octet-stream"), new ByteArrayIO(new byte[] { 0, 1, 2, 3, 4, 5}, "the_file"));

		testRequest("post", new HTTPRequest().post(form).setHeaders(new MimeHeader("X-Test", "a test")), 0, new CheckJSONResponse(form));
	}
	

}
