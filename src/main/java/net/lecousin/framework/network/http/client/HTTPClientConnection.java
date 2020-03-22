package net.lecousin.framework.network.http.client;

import java.io.Closeable;
import java.io.IOException;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.network.client.TCPClient;

/** Abstract class to handle a connection to a HTTP server, with capability
 * to queue requests or to send several requests in parallel. 
 */
public abstract class HTTPClientConnection implements AutoCloseable, Closeable {

	/** Constructor. */
	public HTTPClientConnection(TCPClient tcp, IAsync<IOException> connect) {
		this.tcp = tcp;
		this.connect = connect;
	}
	
	protected TCPClient tcp;
	protected IAsync<IOException> connect;
	protected boolean stopping = false;
	
	public boolean isConnected() {
		return !stopping && connect.isSuccessful() && !tcp.isClosed();
	}
	
	public boolean isClosed() {
		return stopping;
	}
	
	@Override
	public void close() {
		stopping = true;
		tcp.close();
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

}
