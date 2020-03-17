package net.lecousin.framework.network.http.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http1.client.HTTP1ClientConnection;
import net.lecousin.framework.network.http1.client.HTTP1ClientUtil;
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
	private long lastNewConnectionFailed = -1;
	private long lastConnectionFailed = -1;
	private long lastUsage = 0;
	
	public InetSocketAddress getAddress() {
		return serverAddress;
	}
	
	void close() {
		synchronized (openConnections) {
			ArrayList<HTTPClientConnection> list = new ArrayList<>(openConnections);
			for (HTTPClientConnection c : list)
				c.close();
			openConnections.clear();
		}
	}
	
	String getDescription() {
		return "Connection to " + serverAddress + ": " + openConnections.size();
	}
	
	boolean isUsed() {
		synchronized (openConnections) {
			for (HTTPClientConnection c : openConnections) {
				if (c.getIdleTime() <= 0)
					return true;
			}
		}
		return false;
	}
	
	long lastUsage() {
		synchronized (openConnections) {
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
	
	/** Get a connexion that is open but available. */
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
	
	/** Create a new connection if maximum is not reached, else null is returned. */
	public HTTPClientConnection createConnection(HTTPClientRequestContext reservedFor) {
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
	
	private HTTPClientConnection createDirectConnection(HTTPClientRequestContext reservedFor) {
		TCPClient tcp;
		if (reservedFor.getRequest().isSecure()) {
			@SuppressWarnings("resource")
			SSLClient ssl = new SSLClient(config.getSSLContext());
			ssl.setHostNames(Arrays.asList(reservedFor.getRequest().getHostname()));
			tcp = ssl;
		} else {
			tcp = new TCPClient();
		}
		Async<IOException> connect = tcp.connect(serverAddress, config.getTimeouts().getConnection(), config.getSocketOptionsArray());
		HTTP1ClientConnection connection = new HTTP1ClientConnection(tcp, connect, 2, config);
		return addConnection(connection, tcp, connect, reservedFor);
	}
	
	@SuppressWarnings("java:S2095") // proxyClient will be closed later
	private HTTPClientConnection createProxyConnection(HTTPClientRequestContext reservedFor) {
		HTTPClientRequest request = reservedFor.getRequest();
		Pair<TCPClient, IAsync<IOException>> proxyConnection = request.isSecure()
			? HTTP1ClientUtil.openTunnelOnProxy(serverAddress, request.getHostname(), request.getPort(), true, config, logger)
			: HTTP1ClientUtil.openDirectConnection(serverAddress, config, logger);
			
		HTTP1ClientConnection connection = new HTTP1ClientConnection(proxyConnection.getValue1(), proxyConnection.getValue2(), 2, config);
		return addConnection(connection, proxyConnection.getValue1(), proxyConnection.getValue2(), reservedFor);
	}
	
	private HTTPClientConnection addConnection(
		HTTPClientConnection connection, TCPClient tcp, IAsync<IOException> connect, HTTPClientRequestContext reservedFor
	) {
		openConnections.add(connection);
		connection.reserve(reservedFor);
		connect.onErrorOrCancel(() -> {
			boolean hasOpen = false;
			synchronized (openConnections) {
				for (HTTPClientConnection c : openConnections)
					if (c.isConnected()) {
						hasOpen = true;
						break;
					}
				if (hasOpen)
					lastNewConnectionFailed = System.currentTimeMillis();
				else
					lastConnectionFailed = System.currentTimeMillis();
			}
			if (hasOpen)
				reservedFor.getRemoteAddresses().add(serverAddress);
			client.retryConnection(reservedFor);
		});
		tcp.onclosed(() -> {
			synchronized (openConnections) {
				openConnections.remove(connection);
			}
			client.connectionClosed();
		});
		return connection;
	}
	
}
