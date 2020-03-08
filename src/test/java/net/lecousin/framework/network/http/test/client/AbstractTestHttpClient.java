package net.lecousin.framework.network.http.test.client;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedList;

import net.lecousin.framework.core.test.runners.LCConcurrentRunner;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.test.AbstractHTTPTest;
import net.lecousin.framework.network.mime.entity.BinaryEntity;

import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameter;

@RunWith(LCConcurrentRunner.Parameterized.class)
public abstract class AbstractTestHttpClient extends AbstractHTTPTest {
	
	protected interface ResponseChecker {
		
		void check(HTTPRequest request, HTTPResponse response, IOException error) throws Exception;
		
	}
	
	public static Collection<Object[]> parameters(String... urls) {
		LinkedList<Object[]> list = new LinkedList<>();
		for (int testCase = 1; testCase <= 2; testCase++) {
			for (int i = 0; i < urls.length; ++i) {
				list.add(new Object[] { Integer.valueOf(testCase), urls[i] });
			}
		}
		return list;
	}
	
	@Parameter(0)
	public int testCase;
	@Parameter(1)
	public String baseURL;

	protected void testRequest(String path, HTTPRequest request, int maxRedirect, ResponseChecker... checkers) throws Exception {
		URI uri = new URI(baseURL + path);
		request.setURI(uri);
		
		LinkedList<AutoCloseable> toClose = new LinkedList<>();

		try {
			HTTPClient client = HTTPClient.create(uri);
			toClose.add(client);
			HTTPResponse response;
			try {
				switch (testCase) {
				case 1: // receive full body
					response = client.sendAndReceive(request, null, BinaryEntity::new, maxRedirect).blockResult(0);
					break;
				case 2: // start receiving body
					response = client.sendAndReceiveHeadersThenBodyAsBinary(request, 64 * 1024, maxRedirect).blockResult(0);
					break;
				default:
					throw new AssertionError("Unknown test case " + testCase);
				}
				
			} catch (IOException error) {
				for (ResponseChecker checker : checkers)
					checker.check(request, null, error);
				return;
			}
			for (ResponseChecker checker : checkers)
				checker.check(request, response, null);
		} finally {
			for (AutoCloseable c : toClose)
				c.close();
		}
	}
	
}
