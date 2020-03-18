package net.lecousin.framework.network.http.client;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.rmi.ConnectException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.memory.IMemoryManageable;
import net.lecousin.framework.memory.MemoryManager;
import net.lecousin.framework.mutable.MutableInteger;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.mime.entity.MimeEntity;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.transfer.ChunkedTransfer;
import net.lecousin.framework.text.ByteArrayStringIso8859Buffer;
import net.lecousin.framework.util.Pair;

/**
 * HTTP Client, managing connections to servers.<br/>
 *
 */
public class HTTPClient implements AutoCloseable, Closeable, IMemoryManageable {
	
	/** Get the default instance for the current application. */
	public static synchronized HTTPClient getDefault() {
		 Application app = LCCore.getApplication();
		 HTTPClient client = app.getInstance(HTTPClient.class);
		 if (client == null) {
			 client = new HTTPClient(new HTTPClientConfiguration());
			 app.setInstance(HTTPClient.class, client);
		 }
		 return client;
	}
	
	/** Constructor. */
	public HTTPClient(HTTPClientConfiguration config) {
		this.config = config;
		LCCore.getApplication().toClose(0, this);
		MemoryManager.register(this);
	}
	
	private HTTPClientConfiguration config;
	private Map<InetSocketAddress, HTTPClientConnectionManager> connectionManagers = new HashMap<>();
	private MutableInteger nbOpenConnections = new MutableInteger(0);
	private LinkedList<HTTPClientRequestContext> queue = new LinkedList<>();
	private boolean closed = false;
	
	public HTTPClientConfiguration getConfiguration() {
		return config;
	}
	
	@Override
	public void close() {
		closed = true;
		synchronized (connectionManagers) {
			for (HTTPClientConnectionManager manager : connectionManagers.values())
				manager.close();
			connectionManagers.clear();
		}
		for (HTTPClientRequestContext r : queue)
			r.getRequestSent().cancel(new CancelException("HTTPClient closed"));
		queue.clear();
		MemoryManager.unregister(this);
		LCCore.getApplication().closed(this);
	}
	
	@Override
	public String getDescription() {
		return "HTTP Client";
	}
	
	@Override
	public List<String> getItemsDescription() {
		synchronized (connectionManagers) {
			ArrayList<String> list = new ArrayList<>(connectionManagers.size());
			for (HTTPClientConnectionManager manager : connectionManagers.values())
				list.add(manager.getDescription());
			return list;
		}
	}
	
	@Override
	public void freeMemory(FreeMemoryLevel level) {
		List<InetSocketAddress> toRemove = new LinkedList<>();
		long now = System.currentTimeMillis();
		synchronized (connectionManagers) {
			for (Map.Entry<InetSocketAddress, HTTPClientConnectionManager> entry : connectionManagers.entrySet()) {
				HTTPClientConnectionManager manager = entry.getValue();
				if (manager.isUsed())
					continue;
				switch (level) {
				default:
				case EXPIRED_ONLY:
					if (connectionManagers.size() > 250 && now - manager.lastUsage() > 5 * 60 * 1000)
						toRemove.add(entry.getKey());
					break;
				case LOW:
					if (connectionManagers.size() > 100 && now - manager.lastUsage() > 2 * 60 * 1000)
						toRemove.add(entry.getKey());
					break;
				case MEDIUM:
					if (connectionManagers.size() > 50 && now - manager.lastUsage() > 30 * 1000)
						toRemove.add(entry.getKey());
					break;
				case URGENT:
					toRemove.add(entry.getKey());
				}
			}
			for (InetSocketAddress a : toRemove) {
				HTTPClientConnectionManager manager = connectionManagers.remove(a);
				manager.close();
			}
		}
	}

	/**
	 * Send an HTTP request, with an optional body.<br/>
	 * Filters configured in the HTTPClientConfiguration are first called to modify the request.
	 */
	public HTTPClientResponse send(HTTPClientRequest request) {
		HTTPClientRequestContext ctx = new HTTPClientRequestContext(this, request);
		send(ctx);
		return ctx.getResponse();
	}
	
	/**
	 * Send an HTTP request, with an optional body.<br/>
	 * Filters configured in the HTTPClientConfiguration are first called to modify the request.
	 */
	public void send(HTTPClientRequestContext ctx) {
		ctx.setClient(this);
		
		// apply filters
		for (HTTPClientRequestFilter filter : config.getFilters())
			filter.filter(ctx.getRequest(), ctx.getResponse());
		
		// determine how to connect
		AsyncSupplier<HTTPClientConnection, IOException> connection = getConnection(ctx);
		
		// start to prepare request while connecting
		MimeEntity entity = ctx.getRequest().getEntity();
		AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> bodyProducer;
		if (entity != null)
			bodyProducer = entity.createBodyProducer();
		else
			bodyProducer = new AsyncSupplier<>(new Pair<>(Long.valueOf(0), new AsyncProducer.Empty<>()), null);
		
		bodyProducer.thenStart("Prepare HTTP request", Task.getCurrentPriority(), (Task<Void, NoException> t) -> {
			sendRequest(ctx, connection, bodyProducer);
			return null;
		}, ctx.getRequestSent());
	}
	
	private void sendRequest(
		HTTPClientRequestContext ctx,
		AsyncSupplier<HTTPClientConnection, IOException> connection,
		AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> bodyProducer
	) {
		HTTPRequest request = ctx.getRequest();
		Long size = bodyProducer.getResult().getValue1();
		Supplier<List<MimeHeader>> trailerSupplier = request.getTrailerHeadersSuppliers();
		
		if (size == null || trailerSupplier != null) {
			request.setHeader(MimeHeaders.TRANSFER_ENCODING, ChunkedTransfer.TRANSFER_NAME);
		} else if (size.longValue() > 0 || HTTPRequest.methodMayContainBody(request.getMethod())) {
			request.getHeaders().setContentLength(size.longValue());
		}
		
		ctx.setRequestBody(bodyProducer.getResult());
		connection.thenStart("Send HTTP request", Priority.NORMAL, (Task<Void, NoException> t) -> {
			if (ctx.getRequestSent().isDone())
				return null; // error or cancel
			connection.getResult().send(ctx).onDone(taken -> {
				if (!taken.booleanValue())
					retryToConnect(ctx);
			});
			return null;
		}, ctx.getRequestSent());
	}
	
	private AsyncSupplier<HTTPClientConnection, IOException> getConnection(HTTPClientRequestContext ctx) {
		AsyncSupplier<List<InetSocketAddress>, IOException> proxy = getProxy(ctx.getRequest());
		AsyncSupplier<HTTPClientConnection, IOException> result = new AsyncSupplier<>();
		proxy.onDone(list -> {
			if (list != null) {
				getConnection(list, true, ctx, result);
				return;
			}
			Task.unmanaged("DNS resolution", Priority.NORMAL, t -> {
				try {
					InetAddress[] ips = InetAddress.getAllByName(ctx.getRequest().getHostname());
					int port = ctx.getRequest().getPort();
					List<InetSocketAddress> addresses = new ArrayList<>(ips.length);
					for (InetAddress ip : ips)
						addresses.add(new InetSocketAddress(ip, port));
					getConnection(addresses, false, ctx, result);
				} catch (UnknownHostException e) {
					ctx.getRequestSent().error(e);
				}
				return null;
			}).start();
		}, ctx.getRequestSent());
		return result;
	}
	
	private void getConnection(
		List<InetSocketAddress> list, boolean isProxy,
		HTTPClientRequestContext ctx,
		AsyncSupplier<HTTPClientConnection, IOException> result
	) {
		ctx.setRemoteAddresses(list);
		ctx.setThroughProxy(isProxy);
		synchronized (connectionManagers) {
			HTTPClientConnection connection = tryToConnect(ctx);
			if (connection != null)
				result.unblockSuccess(connection);
			else
				queue.add(ctx);
		}
	}
	
	@SuppressWarnings("java:S2095")
	private HTTPClientConnection tryToConnect(HTTPClientRequestContext reservedFor) {
		if (closed) {
			reservedFor.getRequestSent().cancel(new CancelException("HTTPClient closed"));
			return null;
		}
		// try to reuse existing available connection
		for (InetSocketAddress addr : reservedFor.getRemoteAddresses()) {
			HTTPClientConnectionManager manager = connectionManagers.get(addr);
			if (manager == null) continue;
			HTTPClientConnection connection = manager.reuseAvailableConnection(reservedFor);
			if (connection != null) {
				connectionUsed(manager, reservedFor);
				return connection;
			}
		}
		if (nbOpenConnections.get() < config.getLimits().getOpenConnections()) {
			// we can open a new connection, we prefer to do so
			for (InetSocketAddress addr : reservedFor.getRemoteAddresses()) {
				HTTPClientConnectionManager manager = connectionManagers.get(addr);
				if (manager == null) {
					manager = new HTTPClientConnectionManager(this, addr, reservedFor.isThroughProxy(), config);
					connectionManagers.put(addr, manager);
					HTTPClientConnection connection = manager.createConnection(reservedFor);
					nbOpenConnections.inc();
					connectionUsed(manager, reservedFor);
					return connection;
				}
			}
			for (InetSocketAddress addr : reservedFor.getRemoteAddresses()) {
				HTTPClientConnectionManager manager = connectionManagers.get(addr);
				HTTPClientConnection connection = manager.createConnection(reservedFor);
				if (connection != null) {
					nbOpenConnections.inc();
					connectionUsed(manager, reservedFor);
					return connection;
				}
			}
		}
		// no available connexion, try to find a connection with a single pending request
		for (InetSocketAddress addr : reservedFor.getRemoteAddresses()) {
			HTTPClientConnectionManager manager = connectionManagers.get(addr);
			if (manager == null) continue;
			HTTPClientConnection connection = manager.reuseConnectionIfPossible(reservedFor);
			if (connection != null) {
				connectionUsed(manager, reservedFor);
				return connection;
			}
		}
		// nothing available
		return null;
	}
	
	private AsyncSupplier<List<InetSocketAddress>, IOException> getProxy(HTTPClientRequest request) {
		ProxySelector proxySelector = config.getProxySelector();
		if (proxySelector == null)
			return new AsyncSupplier<>(null, null);
		AsyncSupplier<List<InetSocketAddress>, IOException> result = new AsyncSupplier<>();
		Task.unmanaged("Get proxy for HTTP request", Priority.NORMAL, t -> {
			URI uri = request.generateURI();
			List<Proxy> proxies = proxySelector.select(uri);
			List<InetSocketAddress> addresses = new LinkedList<>();
			for (Proxy p : proxies) {
				switch (p.type()) {
				case DIRECT:
					result.unblockSuccess(null);
					return null;
				case HTTP:
					addresses.add((InetSocketAddress)p.address());
					break;
				default: break;
				}
			}
			if (addresses.isEmpty()) {
				result.unblockSuccess(null);
				return null;
			}
			// insert scheme, host and port
			ByteArrayStringIso8859Buffer path = request.getEncodedPath();
			path.addFirst(
				(request.isSecure() ? "https" : "http") + "://"
				+ request.getHostname() + ":"
				+ request.getPort()
			);
			request.setEncodedPath(path);
			result.unblockSuccess(addresses);
			return null;
		}).start();
		return result;
	}
	
	private void connectionUsed(HTTPClientConnectionManager manager, HTTPClientRequestContext ctx) {
		ctx.getRemoteAddresses().remove(manager.getAddress());
		ctx.getRequestSent().onDone(() -> {
			synchronized (connectionManagers) {
				if (queue.isEmpty())
					return;
				for (Iterator<HTTPClientRequestContext> it = queue.iterator(); it.hasNext(); ) {
					HTTPClientRequestContext c = it.next();
					if (!c.getRemoteAddresses().contains(manager.getAddress()))
						continue;
					if (retryToConnect(c)) {
						it.remove();
						return;
					}
				}
			}
		});
	}
	
	/** Called by HTTPClientConnectionManager to signal a connection has been closed. */
	void connectionClosed() {
		synchronized (connectionManagers) {
			nbOpenConnections.dec();
			if (queue.isEmpty())
				return;
			for (Iterator<HTTPClientRequestContext> it = queue.iterator(); it.hasNext(); ) {
				HTTPClientRequestContext ctx = it.next();
				if (retryToConnect(ctx)) {
					it.remove();
				}
			}
		}
	}
	
	private boolean retryToConnect(HTTPClientRequestContext ctx) {
		HTTPClientConnection connection = tryToConnect(ctx);
		if (connection == null)
			return false;
		if (ctx.getRequestSent().isDone())
			return true; // error or cancel
		connection.send(ctx).onDone(taken -> {
			if (!taken.booleanValue())
				Task.cpu("HTTP client retry to connect", Priority.NORMAL, t -> {
					synchronized (connectionManagers) {
						retryToConnect(ctx);
					}
					return null;
				}).start();
		});
		return true;
	}
	
	/** Called by HTTPClientConnectionManager to signal connection failed. */
	void retryConnection(HTTPClientRequestContext ctx) {
		if (ctx.getRemoteAddresses().isEmpty()) {
			ctx.getRequestSent().error(new ConnectException("Unable to connect"));
			return;
		}
		synchronized (connectionManagers) {
			if (!retryToConnect(ctx))
				queue.addFirst(ctx);
		}
	}

}
