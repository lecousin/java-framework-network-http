package net.lecousin.framework.network.http.server;

import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.network.http.HTTPResponse;

public class HTTPServerResponse extends HTTPResponse {

	/** Indicates if the connection with the client must be closed once sent. */
	public boolean forceClose = false;
	
	public SynchronizationPoint<Exception> sent = new SynchronizationPoint<>();
	
}
