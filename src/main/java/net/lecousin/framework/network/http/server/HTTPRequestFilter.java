package net.lecousin.framework.network.http.server;

import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.server.TCPServerClient;

/**
 * An HTTP request filter may be used to modify a request, or to process it completely.
 */
public interface HTTPRequestFilter {

	/**
	 * If a synchronization point is returned, it means the request is completely processed by this filter
	 * and nothing more should be done.
	 * If null is returned, the next filters are called, and the request finally processed.
	 * Even null is returned, the request may have been modified by a filter.
	 */
	ISynchronizationPoint<?> filter(TCPServerClient client, HTTPRequest request, HTTPServerResponse response);
	
}
