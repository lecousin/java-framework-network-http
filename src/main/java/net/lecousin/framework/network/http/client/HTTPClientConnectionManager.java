package net.lecousin.framework.network.http.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration.Protocol;
import net.lecousin.framework.network.http.client.HTTPClientConnection.OpenConnection;
import net.lecousin.framework.network.ssl.SSLConnectionConfig;
import net.lecousin.framework.util.Pair;

class HTTPClientConnectionManager {

	public HTTPClientConnectionManager(HTTPClient client, InetSocketAddress serverAddress, boolean isProxy, HTTPClientConfiguration config) {
		this.client = client;
		this.serverAddress = serverAddress;
		this.isProxy = isProxy;
		this.maxConnections = isProxy ? config.getLimits().getConnectionsToProxy() : config.getLimits().getConnectionsToServer();
		this.config = config;
		this.logger = LCCore.getApplication().getLoggerFactory().getLogger(HTTPClientConnectionManager.class);
	}
	
	private Logger logger;
	private HTTPClient client;
	private InetSocketAddress serverAddress;
	private boolean isProxy;
	private int maxConnections;
	private HTTPClientConfiguration config;
	private LinkedList<HTTPClientConnection> openConnections = new LinkedList<>();
	private LinkedList<Pair<AsyncSupplier<HTTPClientConnection, IOException>, List<HTTPClientRequestContext>>> openingConnections =
		new LinkedList<>();
	private long lastNewConnectionFailed = -1;
	private long lastConnectionFailed = -1;
	private long lastUsage = 0;
	
	public InetSocketAddress getAddress() {
		return serverAddress;
	}
	
	void close() {
		List<HTTPClientRequestContext> requests = new LinkedList<>();
		List<AsyncSupplier<HTTPClientConnection, IOException>> opening = new LinkedList<>();
		synchronized (openConnections) {
			ArrayList<HTTPClientConnection> list = new ArrayList<>(openConnections);
			for (HTTPClientConnection c : list)
				c.close();
			openConnections.clear();
			for (Pair<AsyncSupplier<HTTPClientConnection, IOException>, List<HTTPClientRequestContext>> p : openingConnections) {
				requests.addAll(p.getValue2());
				p.getValue2().clear();
				opening.add(p.getValue1());
			}
			openingConnections.clear();
		}
		for (AsyncSupplier<HTTPClientConnection, IOException> o : opening)
			o.onDone(HTTPClientConnection::close);
		for (HTTPClientRequestContext r : requests)
			client.retryConnection(r);
	}
	
	String getDescription() {
		StringBuilder s = new StringBuilder(256);
		s.append("Connection to ").append(serverAddress).append(": ");
		synchronized (openConnections) {
			s.append(openConnections.size());
			for (HTTPClientConnection c : openConnections)
				s.append("\n    - ").append(c.getDescription());
			if (!openingConnections.isEmpty())
				s.append("\n    - ").append(openingConnections.size()).append(" connecting");
		}
		return s.toString();
	}
	
	boolean isUsed() {
		synchronized (openConnections) {
			if (!openingConnections.isEmpty())
				return true;
			for (HTTPClientConnection c : openConnections) {
				if (c.getIdleTime() <= 0)
					return true;
			}
		}
		return false;
	}
	
	boolean closeOneIdleConnection() {
		synchronized (openConnections) {
			for (HTTPClientConnection c : openConnections) {
				if (c.getIdleTime() > 0) {
					c.close();
					return true;
				}
			}
		}
		return false;
	}
	
	long lastUsage() {
		synchronized (openConnections) {
			if (!openingConnections.isEmpty())
				return System.currentTimeMillis();
			long last = lastUsage;
			for (HTTPClientConnection c : openConnections) {
				long idle = c.getIdleTime();
				if (idle <= 0)
					return System.currentTimeMillis();
				if (idle > last)
					last = idle;
			}
			return last;
		}
	}
	
	/** Get a connection that is open but available. */
	public HTTPClientConnection reuseAvailableConnection(HTTPClientRequestContext reservedFor) {
		synchronized (openConnections) {
			for (HTTPClientConnection c : openConnections) {
				if (!c.hasPendingRequest() && !c.isClosed()) {
					c.reserve(reservedFor);
					lastUsage = System.currentTimeMillis();
					return c;
				}
			}
		}
		return null;
	}
	
	public Pair<Integer, HTTPClientConnection> getBestReusableConnection() {
		// TODO
		return null;
	}
	
	public void reuseConnection(HTTPClientConnection connection, HTTPClientRequestContext reservedFor) {
		synchronized (openConnections) {
			connection.reserve(reservedFor);
			lastUsage = System.currentTimeMillis();
		}
	}
	
	/** Create a new connection if maximum is not reached, else null is returned. */
	public AsyncSupplier<HTTPClientConnection, IOException> createConnection(HTTPClientRequestContext reservedFor) {
		synchronized (openConnections) {
			if (openConnections.size() >= maxConnections)
				return null;
			if (!openConnections.isEmpty() && lastNewConnectionFailed != -1 &&
				System.currentTimeMillis() - lastNewConnectionFailed < 10000)
				return null;

			lastUsage = System.currentTimeMillis();
			if (isProxy)
				return createProxyConnection(reservedFor);
			return createDirectConnection(reservedFor);
		}
	}
	
	/** Get an open connection that is eligible for reuse. */
	public HTTPClientConnection reuseConnectionIfPossible(HTTPClientRequestContext reservedFor) {
		synchronized (openConnections) {
			for (HTTPClientConnection c : openConnections) {
				if (c.isAvailableForReuse() && !c.isClosed()) {
					c.reserve(reservedFor);
					lastUsage = System.currentTimeMillis();
					return c;
				}
			}
		}
		return null;
	}
	
	private AsyncSupplier<HTTPClientConnection, IOException> createDirectConnection(HTTPClientRequestContext reservedFor) {
		return addConnection(HTTPClientConnection.openDirectHTTPClientConnection(
			serverAddress, reservedFor.getRequest().getHostname(), reservedFor.getRequest().isSecure(), config, logger
		), reservedFor);
	}
	
	@SuppressWarnings("java:S2095") // proxyClient will be closed later
	private AsyncSupplier<HTTPClientConnection, IOException> createProxyConnection(HTTPClientRequestContext reservedFor) {
		HTTPClientRequest request = reservedFor.getRequest();
		OpenConnection proxyConnection;
		if (!request.isSecure()) {
			HTTPClientConfiguration proxyConfig = new HTTPClientConfiguration(config);
			proxyConfig.setAllowedProtocols(Arrays.asList(Protocol.HTTP1, Protocol.HTTP1S));
			proxyConnection = HTTPClientConnection.openDirectClearConnection(serverAddress, proxyConfig, logger);
		} else {
			SSLConnectionConfig sslConfig = HTTPClientConnection.createSSLConfig(request.getHostname(), config);
			proxyConnection = HTTPProxyUtil.openTunnelThroughHTTPProxy(serverAddress, request.getHostname(), request.getPort(),
				config, sslConfig, logger);
		}
		
		return addConnection(HTTPClientConnection.createHTTPClientConnection(proxyConnection, config), reservedFor);
	}
	
	private AsyncSupplier<HTTPClientConnection, IOException> addConnection(
		AsyncSupplier<HTTPClientConnection, IOException> opening, HTTPClientRequestContext reservedFor
	) {
		AsyncSupplier<HTTPClientConnection, IOException> result = new AsyncSupplier<>();
		List<HTTPClientRequestContext> requests = new LinkedList<>();
		requests.add(reservedFor);
		if (opening.isDone()) {
			if (opening.isSuccessful()) {
				addConnection(opening.getResult(), requests, result);
			} else {
				connectionFailed(result, opening.getError(), requests);
			}
			return result;
		}
		Pair<AsyncSupplier<HTTPClientConnection, IOException>, List<HTTPClientRequestContext>> p = new Pair<>(result, requests);
		openingConnections.add(p);
		opening.thenStart("HTTP Connection done", Priority.NORMAL, (Task<Void, NoException> t) -> {
			synchronized (openConnections) {
				openingConnections.remove(p);
				if (opening.isSuccessful()) {
					addConnection(opening.getResult(), requests, result);
				} else {
					connectionFailed(result, opening.getError(), requests);
				}
			}
			return null;
		}, true);
		return result;
	}
	
	private void addConnection(
		HTTPClientConnection conn, List<HTTPClientRequestContext> requests, AsyncSupplier<HTTPClientConnection, IOException> result
	) {
		openConnections.add(conn);
		for (HTTPClientRequestContext r : requests)
			conn.reserve(r);
		conn.tcp.onclosed(() ->
			Task.cpu("Connection closed", Priority.RATHER_IMPORTANT, t -> {
				synchronized (openConnections) {
					openConnections.remove(conn);
				}
				client.connectionClosed();
				return null;
			}).start()
		);
		result.unblockSuccess(conn);
	}
	
	private void connectionFailed(
		AsyncSupplier<HTTPClientConnection, IOException> result, IOException error, List<HTTPClientRequestContext> requests
	) {
		lastNewConnectionFailed = System.currentTimeMillis();
		Task.cpu("Connection failed", Priority.RATHER_IMPORTANT, t -> {
			boolean hasOpen = false;
			synchronized (openConnections) {
				for (HTTPClientConnection c : openConnections)
					if (c.isConnected()) {
						hasOpen = true;
						break;
					}
				if (!hasOpen)
					lastConnectionFailed = System.currentTimeMillis();
			}
			for (HTTPClientRequestContext r : requests) {
				if (hasOpen) {
					@SuppressWarnings("unchecked")
					List<InetSocketAddress> remoteAddresses = (List<InetSocketAddress>)
						r.getAttribute(HTTPClient.CLIENT_REQUEST_REMOTE_ADDRESSES_ATTRIBUTE);
					remoteAddresses.add(serverAddress);
				}
				client.retryConnection(r);
			}
			return null;
		}).start();
		result.error(error);
	}
	
}
