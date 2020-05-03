package net.lecousin.framework.network.http.test.requests;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Threading;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IOAsInputStream;
import net.lecousin.framework.io.IOFromInputStream;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.out2in.OutputToInput;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientResponse;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http.test.requests.TestRequest.ResponseChecker;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.entity.FormDataEntity;
import net.lecousin.framework.network.mime.entity.FormDataEntity.PartFile;
import net.lecousin.framework.network.mime.entity.FormUrlEncodedEntity;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.Runnables.SupplierThrows;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.Assert;

public class HttpBin {

	public static final String HTTP_BIN_DOMAIN = "eu.httpbin.org";
	
	public static List<TestRequest> getTestRequest() {
		List<TestRequest> tests = new LinkedList<>();
		tests.add(new TestRequest("get", (HTTPClientRequest)new HTTPClientRequest(HTTP_BIN_DOMAIN, false).get("/get").addHeader("X-Test", "a test"), 0, new CheckJSONResponse()));
		tests.add(new TestRequest("gzip", (HTTPClientRequest)new HTTPClientRequest(HTTP_BIN_DOMAIN, false).get("/gzip").addHeader("X-Test", "a test"), 0, new CheckJSONResponse()));
		tests.add(new TestRequest("redirect 1/3", (HTTPClientRequest)new HTTPClientRequest(HTTP_BIN_DOMAIN, false).get("/redirect/1").addHeader("X-Test", "a test"), 3, new CheckJSONResponse()));
		tests.add(new TestRequest("redirect 2/3", (HTTPClientRequest)new HTTPClientRequest(HTTP_BIN_DOMAIN, false).get("/redirect/2").addHeader("X-Test", "a test"), 3, new CheckJSONResponse()));
		tests.add(new TestRequest("redirect 3/3", (HTTPClientRequest)new HTTPClientRequest(HTTP_BIN_DOMAIN, false).get("/redirect/3").addHeader("X-Test", "a test"), 3, new CheckJSONResponse()));
		tests.add(new TestRequest("redirect 4/3", (HTTPClientRequest)new HTTPClientRequest(HTTP_BIN_DOMAIN, false).get("/redirect/4").addHeader("X-Test", "a test"), 3, new CheckError(302)));
		tests.add(new TestRequest("bytes", (HTTPClientRequest)new HTTPClientRequest(HTTP_BIN_DOMAIN, false).get("/bytes/15000").addHeader("X-Test", "a test"), 0, new CheckDataResponse(15000)));
		tests.add(new TestRequest("chunked bytes", (HTTPClientRequest)new HTTPClientRequest(HTTP_BIN_DOMAIN, false).get("/stream-bytes/15000").addHeader("X-Test", "a test"), 0, new CheckDataResponse(15000)));
		
		FormDataEntity formData = new FormDataEntity();
		formData.addField("myfield", "valueofmyfield", StandardCharsets.US_ASCII);
		tests.add(new TestRequest("form data with 1 field", (HTTPClientRequest)new HTTPClientRequest(HTTP_BIN_DOMAIN, false).post("/post", formData).addHeader("X-Test", "a test"), 0, new CheckJSONResponse(formData)));

		formData = new FormDataEntity();
		formData.addField("myfield", "valueofmyfield", StandardCharsets.US_ASCII);
		formData.addField("toto", "titi", StandardCharsets.US_ASCII);
		formData.addField("hello", "world", StandardCharsets.US_ASCII);
		tests.add(new TestRequest("form data with 3 fields", (HTTPClientRequest)new HTTPClientRequest(HTTP_BIN_DOMAIN, false).post("/post", formData).addHeader("X-Test", "a test"), 0, new CheckJSONResponse(formData)));

		formData = new FormDataEntity();
		formData.addFile("myfile", "the_filename", new ParameterizedHeaderValue("application/octet-stream"), new ByteArrayIO(new byte[] { 0, 1, 2, 3, 4, 5}, "the_file"));
		tests.add(new TestRequest("form data with 1 file", (HTTPClientRequest)new HTTPClientRequest(HTTP_BIN_DOMAIN, false).post("/post", formData).addHeader("X-Test", "a test"), 0, new CheckJSONResponse(formData)));

		formData = new FormDataEntity();
		formData.addFile("myfile", "the_filename", new ParameterizedHeaderValue("application/octet-stream"), new ByteArrayIO(new byte[] { 0, 1, 2, 3, 4, 5}, "the_file"));
		formData.addField("myfield", "valueofmyfield", StandardCharsets.US_ASCII);
		tests.add(new TestRequest("form data with 1 file + 1 field", (HTTPClientRequest)new HTTPClientRequest(HTTP_BIN_DOMAIN, false).post("/post", formData).addHeader("X-Test", "a test"), 0, new CheckJSONResponse(formData)));

		formData = new FormDataEntity();
		formData.addFile("myfile", "the_filename", new ParameterizedHeaderValue("application/octet-stream"), new ByteArrayIO(new byte[] { 0, 1, 2, 3, 4, 5}, "the_file"));
		formData.addField("myfield", "valueofmyfield", StandardCharsets.US_ASCII);
		formData.addField("myfield2", "valueofmyfield2", StandardCharsets.US_ASCII);
		formData.addFile("f2", "second.bin", new ParameterizedHeaderValue("application/octet-stream"), new ByteArrayIO(new byte[] { 0, 1, 2, 3, 4, 5}, "the_file"));
		formData.addFile("f3", "third.bin", new ParameterizedHeaderValue("application/octet-stream"), new ByteArrayIO(new byte[] { 0, 1, 2, 3, 4, 5}, "the_file"));
		tests.add(new TestRequest("form data with 3 files + 2 fields", (HTTPClientRequest)new HTTPClientRequest(HTTP_BIN_DOMAIN, false).post("/post", formData).addHeader("X-Test", "a test"), 0, new CheckJSONResponse(formData)));

		formData = new FormDataEntity();
		formData.addField("myfield", "valueofmyfield", StandardCharsets.US_ASCII);
		tests.add(new TestRequest("form data with 1 field unknown size HTTP", new Request1FieldUnknownSizeBuilder(false, formData), 0, new CheckJSONResponse(formData)));
		
		FormUrlEncodedEntity form;
		
		form = new FormUrlEncodedEntity();
		tests.add(new TestRequest("form url empty", (HTTPClientRequest)new HTTPClientRequest(HTTP_BIN_DOMAIN, false).post("/post", form).addHeader("X-Test", "a test"), 0, new CheckJSONResponse(form)));

		form = new FormUrlEncodedEntity();
		form.add("myfield", "myvalue");
		tests.add(new TestRequest("form url with 1 field", (HTTPClientRequest)new HTTPClientRequest(HTTP_BIN_DOMAIN, false).post("/post", form).addHeader("X-Test", "a test"), 0, new CheckJSONResponse(form)));

		form = new FormUrlEncodedEntity();
		form.add("myfield1", "myvalue1");
		form.add("myfield2", "myvalue2");
		form.add("myfield3", "myvalue3");
		tests.add(new TestRequest("form url with 3 fields", (HTTPClientRequest)new HTTPClientRequest(HTTP_BIN_DOMAIN, false).post("/post", form).addHeader("X-Test", "a test"), 0, new CheckJSONResponse(form)));
		
		return tests;
	}
	
	public static class Request1FieldUnknownSizeBuilder implements SupplierThrows<HTTPClientRequest, Exception> {
		public Request1FieldUnknownSizeBuilder(boolean secure, FormDataEntity form) {
			this.secure = secure;
			this.form = form;
		}
		
		private boolean secure;
		private FormDataEntity form;
		
		@Override
		public HTTPClientRequest get() throws Exception {
			HTTPClientRequest request = (HTTPClientRequest)new HTTPClientRequest(HTTP_BIN_DOMAIN, secure).post("/post").addHeader("X-Test", "a test");
			for (MimeHeader h : form.getHeaders().getHeaders())
				request.addHeader(h);
			OutputToInput o2i = new OutputToInput(new ByteArrayIO("test"), "test");
			form.createBodyProducer().blockResult(0).getValue2().toConsumer(o2i.createConsumer(), "test", Task.Priority.NORMAL);
			InputStream is = IOAsInputStream.get(o2i, false);
			IO.Readable io = new IOFromInputStream(is, "test form", Threading.getCPUTaskManager(), Task.Priority.NORMAL);
			BinaryEntity entity = new BinaryEntity(null, request.getHeaders());
			entity.setContent(io);
			request.setEntity(entity);
			return request;
		}
	}
	
	public static class CheckJSONResponse implements ResponseChecker {
		
		public CheckJSONResponse() {
		}
		
		public CheckJSONResponse(FormDataEntity formData) {
			this.formData = formData;
		}
		
		public CheckJSONResponse(FormUrlEncodedEntity form) {
			this.form = form;
		}
		
		private FormDataEntity formData;
		private FormUrlEncodedEntity form;
		
		@Override
		public void check(HTTPClientRequest request, HTTPClientResponse response, IOException error) throws Exception {
			if (error != null)
				throw error;
			if (!response.isSuccess())
				throw new HTTPResponseError(response);
			
			Object o = null;
			try {
				JSONParser parser = new JSONParser();
				o = parser.parse(new InputStreamReader(IOAsInputStream.get(((BinaryEntity)response.getEntity()).getContent(), false)));
				Assert.assertTrue(o instanceof JSONObject);
				JSONObject json = (JSONObject)o;
				checkURL(json, request);
				checkHeaders(json, request);
				if (formData != null)
					checkFormData(json, formData);
				else if (form != null)
					checkForm(json, form);
			} catch (Throwable e) {
				throw new Exception("Error checking JSON response:\n" + response.getHeaders().generateString(1024).asString() + "\n" + o, e);
			}
		}
		
		private static void checkURL(JSONObject json, HTTPRequest request) throws Exception {
			if (json.containsKey("url")) {
				String u = (String)json.get("url");
				URI uri = new URI(u);
				URI expected = new URI(request.getEncodedPath().asString());
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
			
			for (MimeHeader header : request.getHeaders().getHeaders()) {
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

		private static void checkForm(JSONObject json, FormUrlEncodedEntity form) {
			List<Pair<String, String>> expectedFields = form.getParameters();
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
		}
		
	}
	
	public static class CheckError implements ResponseChecker {
		
		private CheckError(int expectedCode) {
			this.expectedCode = expectedCode;
		}
		
		private int expectedCode;
		
		@Override
		public void check(HTTPClientRequest request, HTTPClientResponse response, IOException error) throws Exception {
			if (error != null)
				throw error;
			Assert.assertEquals(expectedCode, response.getStatusCode());
		}
		
	}
	
	public static class CheckDataResponse implements ResponseChecker {
		
		private CheckDataResponse(int expectedSize) {
			this.expectedSize = expectedSize;
		}
		
		private int expectedSize;
		
		@Override
		public void check(HTTPClientRequest request, HTTPClientResponse response, IOException error) throws Exception {
			if (error != null)
				throw error;
			if (!response.isSuccess())
				throw new HTTPResponseError(response);

			IO.Readable data = ((BinaryEntity)response.getEntity()).getContent();
			byte[] buf = new byte[expectedSize + 16384];
			int nb = data.readFullyAsync(ByteBuffer.wrap(buf)).blockResult(0).intValue();
			Assert.assertEquals(expectedSize, nb);
			data.close();
		}
	}
	
}
