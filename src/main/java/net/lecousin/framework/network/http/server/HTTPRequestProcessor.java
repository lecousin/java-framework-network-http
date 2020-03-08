package net.lecousin.framework.network.http.server;

/** Process requests received by the server. */
public interface HTTPRequestProcessor {

	/**
	 * Process the given request received from the given client.<br/>
	 *
	 * <p>The response MUST NOT be sent, but filled, because the format the response should be sent depends
	 * on the underlying protocol (which may be at least HTTP/1 or HTTP/2).</p>
	 * 
	 * <p>Any resource such as a file should be closed once response.getSent() is unblocked.</p>
	 * 
	 * <p>Once the request has been processed, the response.getReady() must be unblocked to signal it
	 * can be sent. At this time, if the response includes a body, it must be available through
	 * response.getEntity().</p>
	 * 
	 * <p>It is allowed to signal the response ready even the response body has not been fully generated.
	 * In this case, the response will start to be sent over the network, and the response body will follow
	 * as soon as some data are available.</p>
	 * 
	 * <p>If the request is expecting a body, the processor must set an entity to the request immediately,
	 * else a default BinaryEntity will be set but the processor won't be able to access it.</p>
	 * 
	 * @param ctx context containing the client, the request received by the server, and the response the response to fill
	 */
	void process(HTTPRequestContext ctx);
	
}
