package net.lecousin.framework.network.http.test.client;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedList;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.core.test.runners.LCConcurrentRunner;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.buffering.MemoryIO;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.test.AbstractHTTPTest;
import net.lecousin.framework.util.Pair;

import org.junit.Assume;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameter;

@RunWith(LCConcurrentRunner.Parameterized.class)
public abstract class AbstractTestHttpClient extends AbstractHTTPTest {
	
	protected interface ResponseChecker {
		
		void check(HTTPRequest request, HTTPResponse response, IOException error) throws Exception;
		
	}
	
	public static Collection<Object[]> parameters(String... urls) {
		LinkedList<Object[]> list = new LinkedList<>();
		for (int testCase = 1; testCase <= 5; testCase++) {
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
		if (maxRedirect > 0)
			Assume.assumeTrue(testCase < 3);
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
					response = client.sendAndReceive(request, true, true, maxRedirect).blockResult(0);
					break;
				case 2: // start receiving body
					response = client.sendAndReceive(request, true, false, maxRedirect).blockResult(0);
					break;
				case 3: { // step by step, with MemoryIO
					client.sendRequest(request).blockThrow(0);
					MemoryIO io = new MemoryIO(2048, "test");
					toClose.add(io);
					AsyncSupplier<HTTPResponse, IOException> headerListener = new AsyncSupplier<>();
					AsyncSupplier<HTTPResponse, IOException> outputListener = new AsyncSupplier<>();
					client.receiveResponse("test", io, 1024, headerListener, outputListener);
					headerListener.blockThrow(0);
					outputListener.blockThrow(0);
					response = headerListener.getResult();
					break;
				}
				case 4: { // send then receive with MemoryIO with headers listener
					client.sendRequest(request).blockThrow(0);
					MemoryIO io = new MemoryIO(2048, "test");
					toClose.add(io);
					AsyncSupplier<HTTPResponse, IOException> headersListener = new AsyncSupplier<>();
					client.receiveResponse(headersListener, io, 512).blockThrow(0);
					io.seekSync(SeekType.FROM_BEGINNING, 0);
					response = headersListener.getResult();
					break;
				}
				case 5: { // send then receive with MemoryIO
					client.sendRequest(request).blockThrow(0);
					AsyncSupplier<HTTPResponse, IOException> bodyReceived = new AsyncSupplier<>();
					client.receiveResponse(null, resp -> new Pair<>(new MemoryIO(1024, "test"), Integer.valueOf(1024)), bodyReceived);
					response = bodyReceived.blockResult(0);
					MemoryIO io = (MemoryIO)response.getMIME().getBodyReceivedAsInput();
					toClose.add(io);
					io.seekSync(SeekType.FROM_BEGINNING, 0);
					break;
				}
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
