package net.lecousin.framework.network.http.server;

/**
 * An HTTP request filter may be used to modify a request, or to process it completely.
 */
public interface HTTPRequestFilter {

	/**
	 * Filter the given request.<br/>
	 * When this methods returns, if response.getReady() is unblocked, no further processing is done
	 * (filters or processor) and the response is sent.
	 */
	void filter(HTTPRequestContext ctx);
	
}
