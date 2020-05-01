package net.lecousin.framework.network.http.client;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration.Protocol;
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
	protected boolean isThroughProxy;
	protected boolean stopping = false;
	
	public void setConnection(TCPClient tcp, Async<IOException> connect, boolean isThroughProxy) {
		this.tcp = tcp;
		this.connect = connect;
		this.isThroughProxy = isThroughProxy;
	}
	
	public boolean isConnected() {
		return !stopping && connect != null && connect.isSuccessful() && !tcp.isClosed();
	}
	
	public boolean isThroughProxy() {
		return isThroughProxy;
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
	
	public static class OpenConnection {
		private TCPClient client;
		private Async<IOException> connect;
		private boolean isThroughProxy;
		
		public OpenConnection(TCPClient client, Async<IOException> connect, boolean isThroughProxy) {
			this.client = client;
			this.connect = connect;
			this.isThroughProxy = isThroughProxy;
		}

		public TCPClient getClient() {
			return client;
		}

		public Async<IOException> getConnect() {
			return connect;
		}

		public boolean isThroughProxy() {
			return isThroughProxy;
		}
		
	}
	
	public static SSLConnectionConfig createSSLConfig(String hostname, HTTPClientConfiguration config) {
		SSLConnectionConfig sslConfig = new SSLConnectionConfig();
		sslConfig.setHostNames(Arrays.asList(hostname));
		sslConfig.setContext(config.getSSLContext());
		if (SSLConnectionConfig.ALPN_SUPPORTED) {
			List<String> alpn = new LinkedList<>();
			for (HTTPClientConfiguration.Protocol p : config.getAllowedProtocols()) {
				if (!p.isSecure())
					continue;
				String name = p.getAlpn();
				if (name != null)
					alpn.add(name);
			}
			if (!alpn.isEmpty())
				sslConfig.setApplicationProtocols(alpn);
		}
		return sslConfig;
	}
	
	/** Open a connection, possibly through a proxy, to the given HTTP server.
	 * It returns the connection with a TCPClient (which my be a SSLClient),
	 * a connection synchronization point to know when we can start using the connection,
	 * and a boolean indicating if the connection is done through a proxy. 
	 * @throws URISyntaxException if the path is relative
	 */
	public static OpenConnection openConnection(
		String hostname, int port, String path, boolean isSecure, HTTPClientConfiguration config, Logger logger
	) throws URISyntaxException {
		if (port <= 0) port = isSecure ? HTTPConstants.DEFAULT_HTTPS_PORT : HTTPConstants.DEFAULT_HTTP_PORT;
		ProxySelector proxySelector = config.getProxySelector();
		if (proxySelector == null)
			return openDirectConnection(new InetSocketAddress(hostname, port), hostname, isSecure, config, logger);
		URI uri = new URI(isSecure ? HTTPConstants.HTTPS_SCHEME : HTTPConstants.HTTP_SCHEME, null, hostname, port, path, null, null);
		List<Proxy> proxies = proxySelector.select(uri);
		Proxy proxy = null;
		for (Proxy p : proxies) {
			switch (p.type()) {
			case DIRECT:
				return openDirectConnection(new InetSocketAddress(hostname, port), hostname, isSecure, config, logger);
			case HTTP:
				if (proxy == null && p.address() instanceof InetSocketAddress)
					proxy = p;
				break;
			default: break;
			}
		}
		if (proxy == null)
			return openDirectConnection(new InetSocketAddress(hostname, port), hostname, isSecure, config, logger);
		return HTTPProxyUtil.openTunnelThroughHTTPProxy((InetSocketAddress)proxy.address(), hostname, port, config,
			isSecure ? createSSLConfig(hostname, config) : null, logger);
	}

	/** Open a direct connection to a HTTP server.
	 * If iSecure is false a clear connection is done else an SSL connection is done.
	 */
	public static OpenConnection openDirectConnection(
		InetSocketAddress serverAddress, String hostname, boolean isSecure, HTTPClientConfiguration config, Logger logger
	) {
		if (isSecure)
			return openDirectSSLConnection(serverAddress, hostname, config, logger);
		return openDirectClearConnection(serverAddress, config, logger);
	}

	/** Open a direct connection to a HTTP/1 server. */
	public static OpenConnection openDirectClearConnection(
		InetSocketAddress serverAddress, HTTPClientConfiguration config, Logger logger
	) {
		// TODO check a clear protocol is allowed
		if (logger.debug())
			logger.debug("Open direct clear connection to HTTP server " + serverAddress);
		TCPClient client = new TCPClient();
		Async<IOException> connect = client.connect(
			serverAddress, config.getTimeouts().getConnection(), config.getSocketOptionsArray());
		return new OpenConnection(client, connect, false);
	}
	
	/** Open a direct SSL connection to a HTTP server. */
	public static OpenConnection openDirectSSLConnection(
		InetSocketAddress serverAddress, String hostname, HTTPClientConfiguration config, Logger logger
	) {
		if (logger.debug())
			logger.debug("Open direct SSL connection to HTTPS server " + serverAddress);
		SSLClient client = new SSLClient(createSSLConfig(hostname, config));
		Async<IOException> connect = client.connect(
			serverAddress, config.getTimeouts().getConnection(), config.getSocketOptionsArray());
		return new OpenConnection(client, connect, false);
	}
	
	public static AsyncSupplier<HTTPClientConnection, IOException> openHTTPClientConnection(
		String hostname, int port, String path, boolean isSecure, HTTPClientConfiguration config, Logger logger
	) throws URISyntaxException {
		return createHTTPClientConnection(openConnection(hostname, port, path, isSecure, config, logger), config);
	}
	
	public static AsyncSupplier<HTTPClientConnection, IOException> openDirectHTTPClientConnection(
		InetSocketAddress address, String hostname, boolean isSecure, HTTPClientConfiguration config, Logger logger
	) {
		return createHTTPClientConnection(openDirectConnection(address, hostname, isSecure, config, logger), config);
	}
	
	@SuppressWarnings("resource")
	public static AsyncSupplier<HTTPClientConnection, IOException> createHTTPClientConnection(
		OpenConnection c, HTTPClientConfiguration config
	) {
		if (!(c.getClient() instanceof SSLClient) || !SSLConnectionConfig.ALPN_SUPPORTED ||
			!config.getAllowedProtocols().contains(Protocol.H2))
			return new AsyncSupplier<>(new HTTP1ClientConnection(c.getClient(), c.getConnect(), c.isThroughProxy(), 2, config), null);
		
		AsyncSupplier<HTTPClientConnection, IOException> result = new AsyncSupplier<>();
		c.getConnect().onDone(() -> {
			// connection is successful
			if (c.getClient() instanceof SSLClient && SSLConnectionConfig.ALPN_SUPPORTED) {
				String alpn = ((SSLClient)c.getClient()).getApplicationProtocol();
				if ("http/1.1".equals(alpn)) {
					HTTP1ClientConnection h1 = new HTTP1ClientConnection(
						c.getClient(), c.getConnect(), c.isThroughProxy(), 2, config);
					result.unblockSuccess(h1);
					return;
				}
				if ("h2".equals(alpn)) {
					HTTP2Client h2 = new HTTP2Client(c.getClient(), c.getConnect(), c.isThroughProxy(), config);
					result.unblockSuccess(h2);
					return;
				}
			}
			// by default, fallback to HTTP/1
			HTTP1ClientConnection h1 = new HTTP1ClientConnection(c.getClient(), c.getConnect(), c.isThroughProxy(), 2, config);
			result.unblockSuccess(h1);
		}, result);
		return result;
	}

}
