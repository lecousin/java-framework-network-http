package net.lecousin.framework.network.http.client;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.ByteBuffer;
import java.rmi.ConnectException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.application.Version;
import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.memory.IMemoryManageable;
import net.lecousin.framework.memory.MemoryManager;
import net.lecousin.framework.mutable.MutableInteger;
import net.lecousin.framework.network.cache.HostKnowledgeCache;
import net.lecousin.framework.network.cache.HostPortKnowledge;
import net.lecousin.framework.network.cache.HostProtocol;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.header.AlternativeService;
import net.lecousin.framework.network.http.header.AlternativeServices;
import net.lecousin.framework.network.mime.MimeException;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.name.NameService;
import net.lecousin.framework.network.name.NameService.Resolution;
import net.lecousin.framework.util.Pair;

/**
 * HTTP Client, managing connections to servers.<br/>
 *
 */
public class HTTPClient implements AutoCloseable, Closeable, IMemoryManageable, HTTPClientRequestSender {
	
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
	
	public static final String HOST_PROTOCOL_ALTERNATIVE_SERVICES_ATTRIBUTE = "HTTP-alternative-services";
	
	static final String CLIENT_REQUEST_REMOTE_ADDRESSES_ATTRIBUTE = "httpclient.request.remote-addresses";
	
	@SuppressWarnings("unchecked")
	public static void addKnowledgeFromResponseHeaders(
		HTTPClientRequest request, HTTPClientResponse response,
		InetSocketAddress serverAddress, boolean throughProxy
	) {
		HostPortKnowledge k = HostKnowledgeCache.get().getOrCreateKnowledge(serverAddress);
		k.used();
		HostProtocol p = k.getOrCreateProtocolByName("HTTP");
		MimeHeaders headers = response.getHeaders();
		if (!throughProxy && p.getImplementation() == null && headers.has(HTTPConstants.Headers.Response.SERVER))
			p.setImplementation(headers.getFirstRawValue(HTTPConstants.Headers.Response.SERVER));
		Version v = new Version("" + response.getProtocolVersion().getMajor() + '.' + response.getProtocolVersion().getMinor());
		if (!p.getVersions().contains(v))
			p.addVersion(v);
		if (!throughProxy) {
			try {
				List<AlternativeServices> alts = headers.getValues(AlternativeService.HEADER, AlternativeServices.class);
				if (!alts.isEmpty()) {
					Map<String, List<Pair<AlternativeService, Long>>> map;
					synchronized (p) {
						map = (Map<String, List<Pair<AlternativeService, Long>>>)
							p.getAttribute(HOST_PROTOCOL_ALTERNATIVE_SERVICES_ATTRIBUTE);
						if (map == null) {
							map = new HashMap<>(5);
							p.setAttribute(HOST_PROTOCOL_ALTERNATIVE_SERVICES_ATTRIBUTE, map);
						}
					}
					synchronized (map) {
						List<Pair<AlternativeService, Long>> known = map.get(request.getHostname());
						if (known == null) {
							known = new LinkedList<>();
							map.put(request.getHostname(), known);
						}
						// clear expired
						long now = System.currentTimeMillis();
						for (Iterator<Pair<AlternativeService, Long>> it = known.iterator(); it.hasNext(); ) {
							Pair<AlternativeService, Long> service = it.next();
							if (service.getValue1().getMaxAge() > 0 &&
								now - service.getValue2().longValue() > service.getValue1().getMaxAge())
								it.remove();
						}
						// add new advertised services
						for (AlternativeServices services : alts) {
							for (AlternativeService service : services.getValues()) {
								if ("clear".equals(service.getProtocolId()))
									known.clear();
								else {
									boolean found = false;
									for (Pair<AlternativeService, Long> s : known) {
										if (s.getValue1().isSame(service)) {
											found = true;
											s.getValue1().setMaxAge(service.getMaxAge());
											s.setValue2(Long.valueOf(now));
											break;
										}
									}
									if (!found)
										known.add(new Pair<>(service, Long.valueOf(now)));
								}
							}
						}
					}
				}
			} catch (MimeException e) {
				LCCore.getApplication().getLoggerFactory().getLogger(HTTPClient.class).error("Error parsing alternative services", e);
			}
		}
	}
	
	/** Constructor. */
	public HTTPClient(HTTPClientConfiguration config) {
		this.config = config;
		Application app = LCCore.getApplication();
		app.toClose(0, this);
		logger = app.getLoggerFactory().getLogger(HTTPClient.class);
		MemoryManager.register(this);
	}
	
	private Logger logger;
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
					if (now - manager.lastUsage() > 5 * 60 * 1000)
						toRemove.add(entry.getKey());
					break;
				case LOW:
					if (now - manager.lastUsage() > 2 * 60 * 1000)
						toRemove.add(entry.getKey());
					break;
				case MEDIUM:
					if (now - manager.lastUsage() > 30 * 1000)
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
	
	@Override
	public void redirectTo(HTTPClientRequestContext ctx, URI targetUri) {
		// TODO we need to keep targetUri else we will connect again to the same host !
		send(ctx);
	}
	
	@Override
	public void send(HTTPClientRequestContext ctx) {
		ctx.removeAttribute(CLIENT_REQUEST_REMOTE_ADDRESSES_ATTRIBUTE);
		ctx.setSender(this);
		
		if (logger.debug())
			logger.debug("Request to send: " + ctx);
		
		// apply filters
		ctx.applyFilters(config.getFilters());
		
		// determine how to connect
		AsyncSupplier<HTTPClientConnection, IOException> connection = getConnection(ctx);
		
		// start to prepare request while connecting
		AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> bodyProducer =
			ctx.prepareRequestBody();
		
		// once request is done we can try to dequeue a request
		ctx.getResponse().getTrailersReceived().thenStart("Dequeue HTTP request", Priority.NORMAL, () -> dequeueRequest(), true);
		
		bodyProducer.thenStart(Task.cpu("Prepare HTTP request", Task.getCurrentPriority(), ctx.getContext(), t -> {
			ctx.setRequestBody(bodyProducer.getResult());
			sendRequest(ctx, connection);
			return null;
		}), ctx.getRequestSent());
	}
	
	private void sendRequest(
		HTTPClientRequestContext ctx,
		AsyncSupplier<HTTPClientConnection, IOException> connection
	) {
		connection.thenStart(Task.cpu("Send HTTP request", Priority.NORMAL, ctx.getContext(), t -> {
			if (ctx.getRequestSent().isDone())
				return null; // error or cancel
			connection.getResult().sendReserved(ctx).onDone(taken -> {
				if (!taken.booleanValue())
					Task.cpu("Retry connection", Priority.NORMAL, ctx.getContext(), task -> {
						synchronized (connectionManagers) {
							queue.addFirst(ctx);
							dequeue();
						}
						return null;
					}).start();
			});
			return null;
		}), ctx.getRequestSent());
	}
	
	private AsyncSupplier<HTTPClientConnection, IOException> getConnection(HTTPClientRequestContext ctx) {
		AsyncSupplier<List<InetSocketAddress>, IOException> proxy = getProxy(ctx);
		AsyncSupplier<HTTPClientConnection, IOException> result = new AsyncSupplier<>();
		proxy.onDone(list -> {
			if (list != null) {
				if (logger.debug()) logger.debug("Using proxy to connect to " + ctx.getRequest().getHostname());
				getConnection(list, true, ctx, result);
				return;
			}
			if (logger.debug()) logger.debug("Using direct connexion to " + ctx.getRequest().getHostname());
			AsyncSupplier<List<Resolution>, IOException> dns = NameService.resolveName(ctx.getRequest().getHostname());
			dns.thenStart(Task.cpu("Get best connection for HTTP request", Priority.NORMAL, ctx.getContext(), t -> {
				List<InetSocketAddress> addresses = new ArrayList<>(dns.getResult().size());
				int port = ctx.getRequest().getPort();
				for (Resolution r : dns.getResult())
					addresses.add(new InetSocketAddress(r.getIp(), port));
				if (logger.debug()) logger.debug("Host " + ctx.getRequest().getHostname() + " resolved into " + addresses);
				getConnection(addresses, false, ctx, result);
				return null;
			}), ctx.getRequestSent());
		}, ctx.getRequestSent());
		return result;
	}
	
	@SuppressWarnings("unchecked")
	private void getConnection(
		List<InetSocketAddress> list, boolean isProxy,
		HTTPClientRequestContext ctx,
		AsyncSupplier<HTTPClientConnection, IOException> result
	) {
		HostKnowledgeCache kcache = HostKnowledgeCache.get();
		for (InetSocketAddress addr : list) {
			HostPortKnowledge hp = kcache.getOrCreateKnowledge(addr);
			HostProtocol p = hp.getProtocolByName("HTTP");
			if (p != null) {
				hp.used();
				Map<String, List<Pair<AlternativeService, Long>>> map;
				synchronized (p) {
					map = (Map<String, List<Pair<AlternativeService, Long>>>)
						p.getAttribute(HOST_PROTOCOL_ALTERNATIVE_SERVICES_ATTRIBUTE);
				}
				List<AlternativeService> alternatives = new LinkedList<>();
				if (map != null) {
					synchronized (map) {
						List<Pair<AlternativeService, Long>> known = map.get(ctx.getRequest().getHostname());
						if (known != null) {
							// clear expired
							long now = System.currentTimeMillis();
							for (Iterator<Pair<AlternativeService, Long>> it = known.iterator(); it.hasNext(); ) {
								Pair<AlternativeService, Long> service = it.next();
								if (service.getValue1().getMaxAge() > 0 &&
									now - service.getValue2().longValue() > service.getValue1().getMaxAge())
									it.remove();
								else
									alternatives.add(service.getValue1());
							}
						}
					}
				}
				// TODO handle alternatives
				if (logger.debug())
					logger.debug("Host " + addr + " is known to be implemented by "
						+ p.getImplementation() + " with HTTP versions "
						+ p.getVersions() + " having alternative services "
						+ alternatives);
			}
		}
		ctx.setAttribute(CLIENT_REQUEST_REMOTE_ADDRESSES_ATTRIBUTE, list);
		ctx.setThroughProxy(isProxy);
		AsyncSupplier<HTTPClientConnection, NoException> conn;
		synchronized (connectionManagers) {
			conn = tryToConnect(ctx);
			if (conn == null || conn.isDone() && conn.getResult() == null) {
				queue.add(ctx);
				return;
			}
		}
		if (conn.isDone() && conn.getResult() != null) {
			result.unblockSuccess(conn.getResult());
			return;
		}
		conn.thenStart(Task.cpu("HTTP Connection", Priority.NORMAL, ctx.getContext(), t -> {
			if (conn.isSuccessful())
				result.unblockSuccess(conn.getResult());
			else {
				synchronized (connectionManagers) {
					queue.add(ctx);
					dequeue();
				}
			}
			return null;
		}), false);
	}
	
	@SuppressWarnings("java:S2095")
	private AsyncSupplier<HTTPClientConnection, NoException> tryToConnect(HTTPClientRequestContext reservedFor) {
		if (closed) {
			reservedFor.getRequestSent().cancel(new CancelException("HTTPClient closed"));
			return null;
		}
		@SuppressWarnings("unchecked")
		List<InetSocketAddress> remoteAddresses =
			(List<InetSocketAddress>)reservedFor.getAttribute(CLIENT_REQUEST_REMOTE_ADDRESSES_ATTRIBUTE);
		
		int bestReuse = 0;
		HTTPClientConnection bestReuseConn = null;
		HTTPClientConnectionManager bestReuseManager = null;
		InetSocketAddress notConnectedAddress = null;
		for (InetSocketAddress addr : remoteAddresses) {
			HTTPClientConnectionManager manager = connectionManagers.get(addr);
			if (manager == null) {
				if (notConnectedAddress == null)
					notConnectedAddress = addr;
				continue;
			}
			HTTPClientConnection connection = manager.reuseAvailableConnection(reservedFor);
			if (connection != null) {
				// open idle connection found, use it
				if (logger.debug()) logger.debug("Reuse available connection on " + addr + " for " + reservedFor);
				connectionUsed(manager, reservedFor);
				return new AsyncSupplier<>(connection, null);
			}
			Pair<Integer, HTTPClientConnection> p = manager.getBestReusableConnection();
			if (p != null && p.getValue1().intValue() > bestReuse) {
				bestReuse = p.getValue1().intValue();
				bestReuseConn = p.getValue2();
				bestReuseManager = manager;
			}
		}
		
		if (notConnectedAddress != null && nbOpenConnections.get() < config.getLimits().getOpenConnections()) {
			// we have a remote address that we don't use yet, and and didn't reach the connection limit => open a new connection
			HTTPClientConnectionManager manager = new HTTPClientConnectionManager(
				this, notConnectedAddress, reservedFor.isThroughProxy(), config);
			connectionManagers.put(notConnectedAddress, manager);
			AsyncSupplier<HTTPClientConnection, NoException> result = createConnection(manager, reservedFor);
			if (result != null)
				return result;
		}
		
		if (bestReuseConn != null) {
			bestReuseManager.reuseConnection(bestReuseConn, reservedFor);
			if (logger.debug()) logger.debug("Reuse connection on " + bestReuseManager.getAddress() + " for " + reservedFor);
			connectionUsed(bestReuseManager, reservedFor);
			return new AsyncSupplier<>(bestReuseConn, null);
		}
		
		if (nbOpenConnections.get() < config.getLimits().getOpenConnections()) {
			// we can open a new connection, we prefer to do so
			for (InetSocketAddress addr : remoteAddresses) {
				HTTPClientConnectionManager manager = connectionManagers.get(addr);
				if (manager == null) continue;
				AsyncSupplier<HTTPClientConnection, NoException> result = createConnection(manager, reservedFor);
				if (result != null)
					return result;
			}
		}
		
		// no available connection, try to find a connection that can handle a new request
		for (InetSocketAddress addr : remoteAddresses) {
			HTTPClientConnectionManager manager = connectionManagers.get(addr);
			if (manager == null) continue;
			HTTPClientConnection connection = manager.reuseConnectionIfPossible(reservedFor);
			if (connection != null) {
				if (logger.debug()) logger.debug("Reuse connection on " + addr + " for " + reservedFor);
				connectionUsed(manager, reservedFor);
				return new AsyncSupplier<>(connection, null);
			}
		}
		
		// if no open connection to one of the addresses, try to close an idle connection
		if (nbOpenConnections.get() >= config.getLimits().getOpenConnections()) {
			for (InetSocketAddress addr : remoteAddresses) {
				HTTPClientConnectionManager manager = connectionManagers.get(addr);
				if (manager != null) continue;
				// we have one address without connection
				for (Map.Entry<InetSocketAddress, HTTPClientConnectionManager> entry : connectionManagers.entrySet()) {
					manager = entry.getValue();
					if (manager.closeOneIdleConnection()) {
						if (logger.debug()) logger.debug("Close idle connection on " + manager.getAddress()
							+ " to open a new one on " + addr);
						nbOpenConnections.dec();
						manager = new HTTPClientConnectionManager(this, addr, reservedFor.isThroughProxy(), config);
						connectionManagers.put(addr, manager);
						AsyncSupplier<HTTPClientConnection, NoException> result = createConnection(manager, reservedFor);
						if (result != null)
							return result;
					}
				}
			}
		}
		// nothing available
		if (logger.debug()) logger.debug("No connection available for " + reservedFor);
		return null;
	}
	
	private AsyncSupplier<HTTPClientConnection, NoException> createConnection(
		HTTPClientConnectionManager manager, HTTPClientRequestContext reservedFor
	) {
		AsyncSupplier<HTTPClientConnection, IOException> create = manager.createConnection(reservedFor);
		if (create == null)
			return null;
		if (create.isDone()) {
			if (create.isSuccessful()) {
				nbOpenConnections.inc();
				if (logger.debug()) logger.debug("New connection on " + manager.getAddress() + " for " + reservedFor);
				connectionUsed(manager, reservedFor);
				return new AsyncSupplier<>(create.getResult(), null);
			}
			return null;
		}
		nbOpenConnections.inc();
		AsyncSupplier<HTTPClientConnection, NoException> result = new AsyncSupplier<>();
		create.thenStart(Task.cpu("HTTP Connection open", Priority.NORMAL, reservedFor.getContext(), t -> {
			synchronized (connectionManagers) {
				if (!create.isSuccessful()) {
					nbOpenConnections.dec();
					result.unblockSuccess(null);
					return null;
				}
				if (logger.debug()) logger.debug("New connection on " + manager.getAddress() + " for " + reservedFor);
				connectionUsed(manager, reservedFor);
				result.unblockSuccess(create.getResult());
			}
			return null;
		}), true);
		return result;
	}
	
	private AsyncSupplier<List<InetSocketAddress>, IOException> getProxy(HTTPClientRequestContext request) {
		ProxySelector proxySelector = config.getProxySelector();
		if (proxySelector == null)
			return new AsyncSupplier<>(null, null);
		AsyncSupplier<List<InetSocketAddress>, IOException> result = new AsyncSupplier<>();
		Task.unmanaged("Get proxy for HTTP request", Priority.NORMAL, request.getContext(), t -> {
			URI uri = request.getRequest().generateURI();
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
			result.unblockSuccess(addresses);
			return null;
		}).start();
		return result;
	}
	
	private static void connectionUsed(HTTPClientConnectionManager manager, HTTPClientRequestContext ctx) {
		@SuppressWarnings("unchecked")
		List<InetSocketAddress> remoteAddresses =
			(List<InetSocketAddress>)ctx.getAttribute(CLIENT_REQUEST_REMOTE_ADDRESSES_ATTRIBUTE);
		remoteAddresses.remove(manager.getAddress());
	}
	
	private void dequeueRequest() {
		if (logger.debug()) logger.debug("Dequeue request: " + queue.size() + " pending");
		synchronized (connectionManagers) {
			dequeue();
		}
	}
	
	/** Called by HTTPClientConnectionManager to signal a connection has been closed. */
	void connectionClosed() {
		synchronized (connectionManagers) {
			nbOpenConnections.dec();
			dequeue();
		}
	}
	
	private void dequeue() {
		if (queue.isEmpty())
			return;
		LinkedList<HTTPClientRequestContext> todo = new LinkedList<>(queue);
		queue.clear();
		for (HTTPClientRequestContext ctx : todo)
			if (!ctx.getRequestSent().isDone()) // error or cancel
				retryToConnect(ctx);
	}
	
	private void retryToConnect(HTTPClientRequestContext ctx) {
		// TODO limit number of retry
		if (logger.debug()) logger.debug("Retry to connect to send " + ctx);
		AsyncSupplier<HTTPClientConnection, NoException> conn = tryToConnect(ctx);
		if (conn == null) {
			queue.add(ctx);
			return;
		}
		if (conn.isDone()) {
			if (conn.getResult() == null) {
				queue.add(ctx);
				return;
			}
			sendReserved(conn.getResult(), ctx);
			return;
		}
		conn.thenStart(Task.cpu("HTTPClientConnection ready", Priority.NORMAL, ctx.getContext(), t -> {
			if (conn.getResult() != null) {
				sendReserved(conn.getResult(), ctx);
			} else {
				synchronized (connectionManagers) {
					queue.add(ctx);
					dequeue();
				}
			}
			return null;
		}), true);
	}
	
	private void sendReserved(HTTPClientConnection connection, HTTPClientRequestContext ctx) {
		connection.sendReserved(ctx).onDone(taken -> {
			if (!taken.booleanValue())
				Task.cpu("HTTP client retry to connect", Priority.NORMAL, t -> {
					synchronized (connectionManagers) {
						queue.addFirst(ctx);
						dequeue();
					}
					return null;
				}).start();
		});
	}
	
	/** Called by HTTPClientConnectionManager to signal connection failed. */
	void retryConnection(HTTPClientRequestContext ctx) {
		@SuppressWarnings("unchecked")
		List<InetSocketAddress> remoteAddresses =
			(List<InetSocketAddress>)ctx.getAttribute(CLIENT_REQUEST_REMOTE_ADDRESSES_ATTRIBUTE);
		if (remoteAddresses.isEmpty()) {
			ctx.getRequestSent().error(new ConnectException("Unable to connect"));
			return;
		}
		synchronized (connectionManagers) {
			queue.addFirst(ctx);
			dequeue();
		}
	}

}
