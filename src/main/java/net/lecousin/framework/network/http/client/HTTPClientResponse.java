package net.lecousin.framework.network.http.client;

import java.io.IOException;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.HTTPProtocolVersion;
import net.lecousin.framework.network.http.HTTPResponse;

/** HTTP response received from a server. */
public class HTTPClientResponse extends HTTPResponse {

	private Async<IOException> headersReceived = new Async<>();
	private Async<IOException> bodyReceived = new Async<>();
	private Async<IOException> trailersReceived = new Async<>();
	
	/** Constructor. */
	public HTTPClientResponse() {
		headersReceived.onError(bodyReceived::error);
		headersReceived.onCancel(bodyReceived::cancel);
		bodyReceived.onError(trailersReceived::error);
		bodyReceived.onCancel(trailersReceived::cancel);
	}

	public Async<IOException> getHeadersReceived() {
		return headersReceived;
	}

	public Async<IOException> getBodyReceived() {
		return bodyReceived;
	}

	public Async<IOException> getTrailersReceived() {
		return trailersReceived;
	}
	
	/** Return true if the underlying connection will be closed after this response. */
	@SuppressWarnings("java:S1126") // last if can be returned directly, but it would be less readable
	public boolean isConnectionClose() {
		String connectionHeader = getHeaders().getFirstRawValue(HTTPConstants.Headers.CONNECTION);
		if (HTTPConstants.Headers.CONNECTION_VALUE_CLOSE.equalsIgnoreCase(connectionHeader))
			return true;
		HTTPProtocolVersion version = getProtocolVersion();
		if (version.getMajor() < 1)
			return true;
		if (version.getMajor() == 1 && version.getMinor() == 0 &&
			!HTTPConstants.Headers.CONNECTION_VALUE_KEEP_ALIVE.equalsIgnoreCase(connectionHeader))
			return true;
		return false;
	}

}
