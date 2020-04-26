package net.lecousin.framework.network.http.client;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;

import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http1.client.HTTP1ClientConnection;
import net.lecousin.framework.network.http2.client.HTTP2Client;
import net.lecousin.framework.network.ssl.SSLConnectionConfig;

/** Abstract class to handle a connection to a HTTP server, with capability
 * to queue requests or to send several requests in parallel. 
 */
public abstract class HTTPClientConnection implements AutoCloseable, Closeable, HTTPClientRequestSender {

	/** Constructor. */
	public HTTPClientConnection() {
		// nothing
	}

	protected TCPClient tcp;
	protected Async<IOException> connect;
	protected boolean stopping = false;
	
	public void setConnection(TCPClient tcp, Async<IOException> connect) {
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
	
	@SuppressWarnings("java:S2095") // SSLClient is given to HTTP1 or HTTP2 client
	public static AsyncSupplier<HTTPClientConnection, IOException> directConnectionUsingALPN(
		String hostname, InetAddress address, int port, HTTPClientConfiguration config
	) {
		if (!SSLConnectionConfig.ALPN_SUPPORTED)
			return new AsyncSupplier<>(null, new IOException("ALPN not supported"));
		SSLConnectionConfig sslConfig = new SSLConnectionConfig();
		sslConfig.setContext(config.getSSLContext());
		sslConfig.setHostNames(Arrays.asList(hostname));
		sslConfig.setApplicationProtocols(Arrays.asList("h2", "http/1.1"));
		SSLClient client = new SSLClient(sslConfig);
		Async<IOException> connect = client.connect(
			new InetSocketAddress(address, port), config.getTimeouts().getConnection(), config.getSocketOptionsArray());
		AsyncSupplier<HTTPClientConnection, IOException> result = new AsyncSupplier<>();
		connect.onDone(() -> {
			String p = client.getApplicationProtocol();
			if (p == null)
				result.error(new IOException("Remote server does not support ALPN"));
			else if ("h2".equals(p)) {
				HTTP2Client h2 = new HTTP2Client(client, connect, config);
				result.unblockSuccess(h2);
			} else if ("http/1.1".equals(p)) {
				HTTP1ClientConnection h1 = new HTTP1ClientConnection(client, connect, 2, config);
				result.unblockSuccess(h1);
			} else {
				result.error(new IOException("Remote server does not support h2 nor http/1.1 protocols"));
			}
		}, result);
		return result;
	}

}
