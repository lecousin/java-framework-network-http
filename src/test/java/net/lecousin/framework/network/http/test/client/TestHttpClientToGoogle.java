package net.lecousin.framework.network.http.test.client;

import java.io.IOException;
import java.util.Collection;

import net.lecousin.framework.io.IO;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPRequest.Method;
import net.lecousin.framework.network.http.HTTPResponse;

import org.junit.Test;
import org.junit.runners.Parameterized.Parameters;

public class TestHttpClientToGoogle extends AbstractTestHttpClient {
	
	private static final String HTTP_GOOGLE = "http://www.google.com/";
	private static final String HTTPS_GOOGLE = "https://www.google.com/";

	@Parameters(name = "case = {0}, base url = {1}")
	public static Collection<Object[]> parameters() {
		return parameters(HTTP_GOOGLE, HTTPS_GOOGLE);
	}

	private static class GetGoogleChecker implements ResponseChecker {
		
		@Override
		public void check(HTTPRequest request, HTTPResponse response, IOException error) throws Exception {
			if (error != null)
				throw error;
			if (response.getStatusCode() != 200) // redirect to local domain
				throw new AssertionError("Status received from Google: " + response.getStatusCode());
			IO.Readable body = response.getMIME().getBodyReceivedAsInput();
			// TODO
			if (body.canStartReading().hasError())
				throw body.canStartReading().getError();
		}
		
	}
	
	@Test
	public void testGetGoogle() throws Exception {
		testRequest("", new HTTPRequest(Method.GET), 3, new GetGoogleChecker());
	}

}
