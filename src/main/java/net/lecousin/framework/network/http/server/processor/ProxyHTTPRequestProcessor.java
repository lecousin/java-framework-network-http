package net.lecousin.framework.network.http.server.processor;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;

import net.lecousin.framework.collections.LinkedArrayList;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.http.server.HTTPRequestFilter;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;
import net.lecousin.framework.network.http1.HTTP1RequestCommandProducer;
import net.lecousin.framework.network.http1.server.HTTP1ServerProtocol;
import net.lecousin.framework.network.server.protocol.TunnelProtocol;
import net.lecousin.framework.text.ByteArrayStringIso8859Buffer;

/**
 * Implements a Proxy.
 * It is also able to respond to local resources requests, by implementing a mapping from a local
 * path to a remote server and path.
 * A list of HTTPRequestFilter may also be used to transform requests before to process them.
 */
public class ProxyHTTPRequestProcessor implements HTTPRequestProcessor {

	/** Constructor. */
	public ProxyHTTPRequestProcessor(int bufferSize, int clientSendTimeout, int remoteSendTimeout, Logger logger) {
		this.logger = logger;
		forwarder = new HTTPRequestForwarder(logger, HTTPClientConfiguration.defaultConfiguration);
		tunnelProtocol = new TunnelProtocol(bufferSize, clientSendTimeout, remoteSendTimeout, logger);
	}
	
	/** Filter that may catch requests.
	 * For example to implement a cache, a filter may check if the resource is already in the cache or not.
	 * It works the same way as an HTTPRequestFilter, but take 2 additional parameters: hostname and port.
	 * @see HTTPRequestFilter 
	 */
	public static interface Filter {
		/** Filter proxy request. 
		 * @see HTTPRequestFilter#filter(HTTPRequestContext)
		 */
		void filter(HTTPRequestContext ctx, String hostname, int port);
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
	private static class LocalMapping {
		private String localPath;
		private String hostname;
		private int port;
		private String path;
		private boolean secure;
	}
	
	@Override
	public void process(HTTPRequestContext ctx) {
		if (logger.trace())
			logger.trace("Request: " + HTTP1RequestCommandProducer.generateString(ctx.getRequest()));

		if (HTTPRequest.METHOD_CONNECT.equals(ctx.getRequest().getMethod())) {
			if (!allowConnect) {
				ctx.getErrorHandler().setError(ctx,
					HttpURLConnection.HTTP_BAD_METHOD, "CONNECT method is not allowed on this server", null);
				return;
			}
			openTunnel(ctx);
			return;
		}
		
		for (HTTPRequestFilter filter : requestFilters) {
			filter.filter(ctx);
			if (ctx.getResponse().getReady().isDone())
				return;
		}
		
		String path = ctx.getRequest().getDecodedPath();

		if (path.length() == 0) {
			ctx.getErrorHandler().setError(ctx, HttpURLConnection.HTTP_BAD_REQUEST, "Empty path", null);
			return;
		}
		if (path.charAt(0) == '/') {
			localPath(ctx);
			return;
		}
		
		int i = path.indexOf(':');
		if (i < 0) {
			ctx.getErrorHandler().setError(ctx, HttpURLConnection.HTTP_NOT_FOUND, "Invalid URL", null);
			return;
		}
		
		String protocol = path.substring(0, i).toLowerCase();
		if (protocol.equals("http")) {
			forwardRequest(ctx);
			return;
		}
		
		if (allowForwardFromHttpToHttps && protocol.equals("https")) {
			forwardHttpsRequest(ctx);
			return;
		}
		
		ctx.getErrorHandler().setError(ctx, HttpURLConnection.HTTP_NOT_FOUND, "Invalid protocol", null);
	}
	
	protected void localPath(HTTPRequestContext ctx) {
		String path = ctx.getRequest().getDecodedPath();
		for (LocalMapping m : localPathMapping) {
			if (path.startsWith(m.localPath)) {
				// forward
				ctx.getRequest().setDecodedPath(m.path + path.substring(m.localPath.length()));
				if (!m.secure)
					forwarder.forward(ctx, m.hostname, m.port);
				else
					forwarder.forwardSSL(ctx, m.hostname, m.port);
				return;
			}
		}
		ctx.getErrorHandler().setError(ctx, HttpURLConnection.HTTP_NOT_FOUND, "Not found", null);
	}
	
	protected void forwardRequest(HTTPRequestContext ctx) {
		forward(ctx, false);
	}

	protected void forwardHttpsRequest(HTTPRequestContext ctx) {
		forward(ctx, true);
	}
	
	private void forward(HTTPRequestContext ctx, boolean ssl) {
		ByteArrayStringIso8859Buffer path = ctx.getRequest().getEncodedPath();
		URI uri;
		try { uri = new URI(path.asString()); }
		catch (Exception t) {
			logger.error("Invalid requested URL: " + path, t);
			ctx.getErrorHandler().setError(ctx, HttpURLConnection.HTTP_BAD_REQUEST, "Invalid URL", t);
			return;
		}
		
		String host = uri.getHost();
		int port = uri.getPort();
		if (port == -1)
			port = ssl ? HTTPConstants.DEFAULT_HTTPS_PORT : HTTPConstants.DEFAULT_HTTP_PORT;
		path = new ByteArrayStringIso8859Buffer(uri.getRawPath());
		
		ctx.getRequest().setEncodedPath(path);
		
		for (Filter filter : proxyFilters) {
			filter.filter(ctx, host, port);
			if (ctx.getResponse().getReady().isDone())
				return;
		}
		
		if (ssl)
			forwarder.forwardSSL(ctx, host, port);
		else
			forwarder.forward(ctx, host, port);
	}
	
	protected void openTunnel(HTTPRequestContext ctx) {
		String host = ctx.getRequest().getDecodedPath();
		int i = host.indexOf(':');
		int port = 80;
		if (i > 0)
			try {
				port = Integer.parseInt(host.substring(i + 1));
				host = host.substring(0, i);
			} catch (Exception t) {
				logger.error("Invalid address " + host, t);
				ctx.getErrorHandler().setError(ctx, HttpURLConnection.HTTP_BAD_REQUEST, "Invalid address " + host, t);
				return;
			}

		for (Filter filter : proxyFilters) {
			filter.filter(ctx, host, port);
			if (ctx.getResponse().getReady().isDone())
				return;
		}
		
		@SuppressWarnings("squid:S2095") // it is closed
		TCPClient tunnel = new TCPClient();
		// take client out of normal protocol
		ctx.getClient().setAttribute(HTTP1ServerProtocol.UPGRADED_PROTOCOL_ATTRIBUTE, tunnelProtocol);
		ctx.getResponse().setForceNoContent(true);
		Async<IOException> connect = tunnel.connect(new InetSocketAddress(host, port), 30000);
		connect.onDone(
			() -> {
				tunnelProtocol.registerClient(ctx.getClient(), tunnel);
				ctx.getResponse().setStatus(200);
				ctx.getResponse().getReady().unblock();
			}, error -> {
				logger.error("Error connecting to remote site", error);
				ctx.getResponse().setStatus(500, "Connection failed to remote site");
				ctx.getResponse().getReady().unblock();
			}, cancel -> {
				ctx.getClient().close();
				tunnel.close();
			}
		);
	}

}
