package net.lecousin.framework.network.http.test.requests;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientResponse;
import net.lecousin.framework.util.Runnables.SupplierThrows;

public class TestRequest {

	public interface ResponseChecker {
		
		void check(HTTPClientRequest request, HTTPClientResponse response, IOException error) throws Exception;
		
	}

	public TestRequest(String name, HTTPClientRequest request, int maxRedirection, ResponseChecker... checkers) {
		this.name = name;
		this.request = request;
		this.maxRedirection = maxRedirection;
		this.checkers = checkers;
	}
	
	public TestRequest(String name, SupplierThrows<HTTPClientRequest, Exception> requestSupplier, int maxRedirection, ResponseChecker... checkers) {
		this.name = name;
		this.requestSupplier = requestSupplier;
		this.maxRedirection = maxRedirection;
		this.checkers = checkers;
	}
	
	public String name;
	public HTTPClientRequest request;
	public SupplierThrows<HTTPClientRequest, Exception> requestSupplier; 
	public int maxRedirection;
	public ResponseChecker[] checkers;
	
	public void init() throws Exception {
		if (requestSupplier != null)
			request = requestSupplier.get();
	}
	
	public void check(HTTPClientResponse response, IOException error) throws Exception {
		for (ResponseChecker checker : checkers)
			checker.check(request, response, error);
	}
	
	public static Collection<Object[]> toParameters(List<? extends TestRequest> tests) {
		Collection<Object[]> list = new ArrayList<>(tests.size());
		for (TestRequest t : tests)
			list.add(new Object[] { t });
		return list;
	}
	
	@Override
	public String toString() {
		if (request == null)
			return name;
		StringBuilder s = new StringBuilder();
		s.append(name).append(": ")
		.append(request.getMethod()).append(' ')
		.append(request.getHostname())
		.append(request.getEncodedPath().asString());
		return s.toString();
	}
	
}
