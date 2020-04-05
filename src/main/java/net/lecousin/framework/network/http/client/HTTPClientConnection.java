package net.lecousin.framework.network.http.client;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;

import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPConstants;

/** Abstract class to handle a connection to a HTTP server, with capability
 * to queue requests or to send several requests in parallel. 
 */
public abstract class HTTPClientConnection implements AutoCloseable, Closeable, HTTPClientRequestSender {

	/** Constructor. */
	public HTTPClientConnection() {
		// nothing
	}

	protected TCPClient tcp;
	protected IAsync<IOException> connect;
	protected boolean stopping = false;
	
	public void setConnection(TCPClient tcp, IAsync<IOException> connect) {
		this.tcp = tcp;
		this.connect = connect;
	}
	
	public boolean isConnected() {
		return !stopping && connect != null && connect.isSuccessful() && !tcp.isClosed();
	}
	
	public boolean isClosed() {
		return stopping;
	}
	
	@Override
	public void close() {
		stopping = true;
		if (tcp != null)
			tcp.close();
		if (!connect.isDone()) connect.cancel(new CancelException("Close connection"));
	}
	
	/** Return true if at least one request is currently processed or queued. */
	public abstract boolean hasPendingRequest();
	
	/** Return true if a new request can be send. */
	public abstract boolean isAvailableForReuse();
	
	/** Return the time the connection enter idle state, or a negative value if it is currently active. */
	public abstract long getIdleTime();
	
	/** Reserve this connection for the given request. */
	public abstract void reserve(HTTPClientRequestContext reservedFor);
	
	/** Send the given request using this connection. The connection must have been reserved previously.
	 * @return true if the request is handled, false if it has not been sent and must be sent to another connection.
	 */
	public abstract AsyncSupplier<Boolean, NoException> sendReserved(HTTPClientRequestContext ctx);
	
	public abstract String getDescription();

	
	/** Return true if the protocol, the hostname and the port are compatible with the client. */
	public static boolean isCompatible(URI uri, TCPClient client, String hostname, int port) {
		String protocol = uri.getScheme();
		if (protocol != null) {
			protocol = protocol.toLowerCase();
			if (client instanceof SSLClient) {
				if (!"https".equals(protocol))
					return false;
			} else if (!"http".equals(protocol)) {
				return false;
			}
		}

		if (!hostname.equals(uri.getHost()))
			return false;
		
		int p = uri.getPort();
		if (p <= 0) {
			if (client instanceof SSLClient)
				p = HTTPConstants.DEFAULT_HTTPS_PORT;
			else
				p = HTTPConstants.DEFAULT_HTTP_PORT;
		}
		return p == port;
	}

}
