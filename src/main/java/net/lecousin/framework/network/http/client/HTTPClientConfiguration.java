package net.lecousin.framework.network.http.client;

import java.net.ProxySelector;
import java.net.SocketOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import javax.net.ssl.SSLContext;

import net.lecousin.framework.network.SocketOptionValue;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.client.filters.EnsureHostFilter;
import net.lecousin.framework.network.http.client.filters.UserAgentFilter;

/** HTTP client configuration. */
public class HTTPClientConfiguration {

	/** HTTP client timeout configuration. */
	public static class Timeouts {
		
		private int connection = 30000;
		private int receive = 60000;
		private int send = 30000;
		private int idle = 3 * 60000;
		
		/** Constructor. */
		public Timeouts() {
			// nothing
		}
		
		/** Copy from existing configuration. */
		public Timeouts(Timeouts copy) {
			connection = copy.connection;
			receive = copy.receive;
			send = copy.send;
			idle = copy.idle;
		}
		
		public int getConnection() {
			return connection;
		}
		
		public void setConnection(int connection) {
			this.connection = connection;
		}
		
		public int getReceive() {
			return receive;
		}
		
		public void setReceive(int receive) {
			this.receive = receive;
		}
		
		public int getSend() {
			return send;
		}
		
		public void setSend(int send) {
			this.send = send;
		}
		
		public int getIdle() {
			return idle;
		}
		
		public void setIdle(int idle) {
			this.idle = idle;
		}
		
	}
	
	/** HTTP client limitations. */
	public static class Limits {
		
		private int headersLength = 128 * 1024;
		private int bodyLength = -1;
		
		private int openConnections = 20;
		private int connectionsToServer = 10;
		private int connectionsToProxy = 20;
		
		/** Constructor. */
		public Limits() {
			// nothing
		}
		
		/** Copy from existing configuration. */
		public Limits(Limits copy) {
			this.headersLength = copy.headersLength;
			this.bodyLength = copy.bodyLength;
			this.openConnections = copy.openConnections;
			this.connectionsToServer = copy.connectionsToServer;
			this.connectionsToProxy = copy.connectionsToProxy;
		}
		
		public int getHeadersLength() {
			return headersLength;
		}
		
		public void setHeadersLength(int headersLength) {
			this.headersLength = headersLength;
		}
		
		public int getBodyLength() {
			return bodyLength;
		}
		
		public void setBodyLength(int bodyLength) {
			this.bodyLength = bodyLength;
		}
		
		public int getOpenConnections() {
			return openConnections;
		}
		
		public void setOpenConnections(int openConnections) {
			this.openConnections = openConnections;
		}
		
		public int getConnectionsToServer() {
			return connectionsToServer;
		}
		
		public void setConnectionsToServer(int connectionsToServer) {
			this.connectionsToServer = connectionsToServer;
		}
		
		public int getConnectionsToProxy() {
			return connectionsToProxy;
		}
		
		public void setConnectionsToProxy(int connectionsToProxy) {
			this.connectionsToProxy = connectionsToProxy;
		}
		
	}
	
	/** HTTP client allowed protocols. */
	public enum Protocol {
		HTTP1(false, null),
		HTTP1S(true, "http/1.1"),
		H2C(false, null),
		H2(true, "h2")
		;
		
		private boolean isSecure;
		private String alpn;
		
		Protocol(boolean isSecure, String alpn) {
			this.isSecure = isSecure;
			this.alpn = alpn;
		}

		public boolean isSecure() {
			return isSecure;
		}

		public String getAlpn() {
			return alpn;
		}
		
	}
	
	private Timeouts timeouts = new Timeouts();
	private Limits limits = new Limits();
	private LinkedList<SocketOptionValue<?>> socketOptions = new LinkedList<>();
	private LinkedList<HTTPClientRequestFilter> filters = new LinkedList<>();
	private ProxySelector proxySelector;
	private SSLContext sslContext = null;
	private LinkedList<Protocol> allowedProtocols;
	
	/** Constructor. */
	public HTTPClientConfiguration() {
		this.allowedProtocols = new LinkedList<>(Arrays.asList(Protocol.H2, Protocol.HTTP1S, Protocol.HTTP1));
		this.filters.add(new UserAgentFilter(HTTPConstants.Headers.Request.DEFAULT_USER_AGENT, false));
		this.filters.add(new EnsureHostFilter());
		try {
			this.sslContext = SSLContext.getDefault();
		} catch (Exception e) {
			// ignore
		}
	}
	
	/** Copy from existing configuration. */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public HTTPClientConfiguration(HTTPClientConfiguration copy) {
		this.timeouts = new Timeouts(copy.timeouts);
		this.limits = new Limits(copy.limits);
		for (SocketOptionValue<?> so : copy.socketOptions)
			socketOptions.add(new SocketOptionValue(so.getOption(), so.getValue()));
		this.filters.addAll(copy.filters);
		this.proxySelector = copy.proxySelector;
		this.sslContext = copy.sslContext;
		this.allowedProtocols = new LinkedList<>(copy.allowedProtocols);
	}

	public Timeouts getTimeouts() {
		return timeouts;
	}

	public void setTimeouts(Timeouts timeouts) {
		this.timeouts = timeouts;
	}

	public Limits getLimits() {
		return limits;
	}

	public void setLimits(Limits limits) {
		this.limits = limits;
	}

	public List<SocketOptionValue<?>> getSocketOptions() {
		return socketOptions;
	}
	
	public SocketOptionValue<?>[] getSocketOptionsArray() {
		return socketOptions.toArray(new SocketOptionValue<?>[socketOptions.size()]);
	}
	
	/** Set an option. */
	@SuppressWarnings("unchecked")
	public <T> void setSocketOption(SocketOptionValue<T> option) {
		for (@SuppressWarnings("rawtypes") SocketOptionValue o : socketOptions)
			if (o.getOption().equals(option.getOption())) {
				o.setValue(option.getValue());
				return;
			}
		socketOptions.add(option);
	}
	
	/** Set an option. */
	@SuppressWarnings("unchecked")
	public <T> void setSocketOption(SocketOption<T> option, T value) {
		for (@SuppressWarnings("rawtypes") SocketOptionValue o : socketOptions)
			if (o.getOption().equals(option)) {
				o.setValue(value);
				return;
			}
		socketOptions.add(new SocketOptionValue<T>(option, value));
	}
	
	/** Get the value for an option or null if not set. */
	@SuppressWarnings("unchecked")
	public <T> T getSocketOption(SocketOption<T> option) {
		for (@SuppressWarnings("rawtypes") SocketOptionValue o : socketOptions)
			if (o.getOption().equals(option))
				return (T)o.getValue();
		return null;
	}
	
	public SSLContext getSSLContext() {
		return sslContext;
	}
	
	public void setSSLContext(SSLContext context) {
		sslContext = context;
	}
	
	/** Return the list of filters. */
	public List<HTTPClientRequestFilter> getFilters() {
		synchronized (filters) {
			return new ArrayList<>(filters);
		}
	}
	
	/** Add a filter. */
	public void appendFilter(HTTPClientRequestFilter interceptor) {
		synchronized (filters) {
			filters.addLast(interceptor);
		}
	}

	/** Add a filter. */
	public void insertFilterFirst(HTTPClientRequestFilter interceptor) {
		synchronized (filters) {
			filters.addFirst(interceptor);
		}
	}
	
	public ProxySelector getProxySelector() {
		return proxySelector;
	}
	
	public void setProxySelector(ProxySelector selector) {
		proxySelector = selector;
	}

	public LinkedList<Protocol> getAllowedProtocols() {
		return allowedProtocols;
	}

	public void setAllowedProtocols(List<Protocol> allowedProtocols) {
		this.allowedProtocols = new LinkedList<>(allowedProtocols);
	}
	
}
