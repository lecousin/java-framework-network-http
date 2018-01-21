package net.lecousin.framework.network.http.server;

import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.server.TCPServerClient;

/** Process requests received by the server. */
public interface HTTPRequestProcessor {

	/**
	 * Process the given request received from the given client.<br/>
	 *
	 * <p>The response MUST NOT be sent, but filled, because the format the response should be sent depends
	 * on the underlying protocol (which may be at least HTTP/1 or HTTP/2).</p>
	 *
	 * <p>The returned synchronization point must be unlocked once the server can start sending the response.
	 * At that time, it is not necessary that the body is fully available, must an IO.Readable must be
	 * set as a body to indicate a body has to be sent. In other words, the synchronization point may be unlocked
	 * while the response body is being generated, but the response status code and headers must be ready to send.
	 * In case the response status code is not set, it is considered as an error and the status
	 * code 500 (Server internal error) will be sent to the client.</p>
	 *
	 * <p>In case an error occured during the process, the synchronization point can be unlocked with an exception.
	 * If this exception is an HTTPResponseError, the status code it contains will be sent to the client.
	 * Any other exception will result into a status code 500.</p>
	 * 
	 * @param client the client
	 * @param request the request received by the server
	 * @param response the response to fill
	 * @return a blocking point which is unblock once the request has been processed, and the response can be sent to the client.
	 */
	public ISynchronizationPoint<?> process(TCPServerClient client, HTTPRequest request, HTTPResponse response);
	
}
