package net.lecousin.framework.network.http.server.processor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.collections.LinkedArrayList;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPRequest.Method;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.server.HTTPRequestFilter;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;
import net.lecousin.framework.network.http.server.HTTPServerProtocol;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.TunnelProtocol;

/**
 * Implements a Proxy.
 * It is also able to respond to local resources requests, by implementing a mapping from a local
 * path to a remote server and path.
 * A list of HTTPRequestFilter may also be used to transform requests before to process them.
 */
public class ProxyHTTPRequestProcessor implements HTTPRequestProcessor {

	/** Constructor. */
	public ProxyHTTPRequestProcessor(int bufferSize, Logger logger) {
		this.logger = logger;
		forwarder = new HTTPRequestForwarder(logger, HTTPClientConfiguration.defaultConfiguration);
		tunnelProtocol = new TunnelProtocol(bufferSize, logger);
	}
	
	/** Filter that may catch requests.
	 * For example to implement a cache, a filter may check if the resource is already in the cache or not.
	 */
	public static interface Filter {
		/** Return null if the request is not filtered, else the synchronization point should be unblocked
		 * when the server can start sending the response (same as HTTPRequestProcessor).
		 */
		ISynchronizationPoint<Exception> filter(HTTPRequest request, HTTPResponse response, String hostname, int port);
	}
	
	protected Logger logger;
	protected HTTPRequestForwarder forwarder;
	protected boolean allowConnect = true;
	protected boolean allowForwardFromHttpToHttps = false;
	protected TunnelProtocol tunnelProtocol;
	protected LinkedArrayList<Filter> proxyFilters = new LinkedArrayList<>(10);
	protected LinkedArrayList<HTTPRequestFilter> requestFilters = new LinkedArrayList<>(10);
	protected LinkedArrayList<LocalMapping> localPathMapping = new LinkedArrayList<>(10);
	
	/** Set how to configure HTTP clients used to forward the requests. */
	public void setHTTPForwardClientConfiguration(HTTPClientConfiguration config) {
		forwarder.setClientConfiguration(config);
	}
	
	/** Specifies if the CONNECT method is allowed or not. */
	public void allowConnectMethod(boolean allowed) {
		allowConnect = allowed;
	}
	
	/** Specifies if it is allowed to request for an HTTPS address using HTTP proxy protocol. */
	public void allowForwardFromHttpToHttps(boolean allowed) {
		allowForwardFromHttpToHttps = allowed;
	}
	
	/** Append a filter. */
	public void addFilter(Filter filter) {
		proxyFilters.add(filter);
	}
	
	/** Append a request filter. */
	public void addFilter(HTTPRequestFilter filter) {
		requestFilters.add(filter);
	}
	
	/** Map a local path to a remote server and path. */
	public void mapLocalPath(String localPath, String targetHostname, int targetPort, String targetPath, boolean targetSSL) {
		LocalMapping m = new LocalMapping();
		m.localPath = localPath;
		m.hostname = targetHostname;
		m.port = targetPort;
		m.path = targetPath;
		m.secure = targetSSL;
		localPathMapping.add(m);
	}
	
	/** Local path mapping. */
	protected static class LocalMapping {
		public String localPath;
		public String hostname;
		public int port;
		public String path;
		public boolean secure;
	}
	
	@Override
	public ISynchronizationPoint<?> process(TCPServerClient client, HTTPRequest request, HTTPResponse response) {
		if (logger.trace())
			logger.trace("Request: " + request.generateCommandLine());

		if (Method.CONNECT.equals(request.getMethod())) {
			if (!allowConnect) {
				response.setStatus(405, "CONNECT method is not allowed on this server");
				return new SynchronizationPoint<>(true);
			}
			return openTunnel(client, request, response);
		}
		
		String path = request.getPath();
		if (path.length() == 0) {
			response.setStatus(404, "No URL specified");
			return new SynchronizationPoint<>(true);
		}
		
		for (HTTPRequestFilter filter : requestFilters) {
			ISynchronizationPoint<?> filtered = filter.filter(client, request, response);
			if (filtered != null)
				return filtered;
		}
		
		path = request.getPath();

		if (path.charAt(0) == '/')
			return localPath(request, response);
		
		int i = path.indexOf(':');
		if (i < 0) {
			response.setStatus(404, "Invalid URL");
			return new SynchronizationPoint<>(true);
		}
		
		String protocol = path.substring(0, i).toLowerCase();
		if (protocol.equals("http"))
			return forwardRequest(request, response);
		
		if (allowForwardFromHttpToHttps && protocol.equals("https"))
			return forwardHttpsRequest(request, response);
		
		response.setStatus(404, "Invalid protocol");
		return new SynchronizationPoint<>(true);
	}
	
	protected ISynchronizationPoint<?> localPath(HTTPRequest request, HTTPResponse response) {
		String path = request.getPath();
		for (LocalMapping m : localPathMapping) {
			if (path.startsWith(m.localPath)) {
				// forward
				request.setPath(m.path + path.substring(m.localPath.length()));
				if (!m.secure)
					return forwarder.forward(request, response, m.hostname, m.port);
				return forwarder.forwardSSL(request, response, m.hostname, m.port);
			}
		}
		response.setStatus(404);
		return new SynchronizationPoint<>(true);
	}
	
	protected ISynchronizationPoint<?> forwardRequest(HTTPRequest request, HTTPResponse response) {
		String path = request.getPath();
		URI uri;
		try { uri = new URI(path); }
		catch (Throwable t) {
			logger.error("Invalid requested URL: " + path, t);
			response.setStatus(500, "Unable to connect");
			return new SynchronizationPoint<>(true);
		}
		
		String host = uri.getHost();
		int port = uri.getPort();
		if (port == -1) port = 80;
		path = uri.getRawPath();
		
		request.setPath(path);
		
		for (Filter filter : proxyFilters) {
			ISynchronizationPoint<Exception> filtered = filter.filter(request, response, host, port);
			if (filtered != null)
				return filtered;
		}
		
		return forwarder.forward(request, response, host, port);
	}

	protected ISynchronizationPoint<?> forwardHttpsRequest(HTTPRequest request, HTTPResponse response) {
		String path = request.getPath();
		URI uri;
		try { uri = new URI(path); }
		catch (Throwable t) {
			logger.error("Invalid requested URL: " + path, t);
			response.setStatus(500, "Unable to connect");
			return new SynchronizationPoint<>(true);
		}
		
		String host = uri.getHost();
		int port = uri.getPort();
		if (port == -1) port = 443;
		path = uri.getRawPath();
		
		request.setPath(path);
		
		for (Filter filter : proxyFilters) {
			ISynchronizationPoint<Exception> filtered = filter.filter(request, response, host, port);
			if (filtered != null)
				return filtered;
		}
		
		return forwarder.forwardSSL(request, response, host, port);
	}
	
	
	private static final byte[] okResponse = "HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] koResponse = "HTTP/1.1 500 Error\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
	
	@SuppressWarnings("resource")
	protected ISynchronizationPoint<?> openTunnel(TCPServerClient client, HTTPRequest request, HTTPResponse response) {
		String host = request.getPath();
		int i = host.indexOf(':');
		int port = 80;
		if (i > 0)
			try {
				port = Integer.valueOf(host.substring(i + 1)).intValue();
				host = host.substring(0, i);
			} catch (Throwable t) {
				logger.error("Invalid address " + host, t);
				response.setStatus(500, "Unable to connect");
				return new SynchronizationPoint<>(true);
			}

		for (Filter filter : proxyFilters) {
			ISynchronizationPoint<Exception> filtered = filter.filter(request, response, host, port);
			if (filtered != null)
				return filtered;
		}
		
		TCPClient tunnel = new TCPClient();
		// take client out of normal protocol
		client.setAttribute(HTTPServerProtocol.UPGRADED_PROTOCOL_ATTRIBUTE, tunnelProtocol);
		SynchronizationPoint<IOException> connect = tunnel.connect(new InetSocketAddress(host, port), 30000);
		connect.listenInline(
			() -> {
				tunnelProtocol.registerClient(client, tunnel);
				client.send(ByteBuffer.wrap(okResponse));
			},
			(error) -> {
				logger.error("Error connecting to remote site", error);
				client.send(ByteBuffer.wrap(koResponse));
			},
			(cancel) -> {
				client.close();
				tunnel.close();
			}
		);
		// never unblock, because we don't want to send a response
		return new SynchronizationPoint<>();
	}

}
