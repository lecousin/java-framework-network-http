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
import java.util.function.Supplier;

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
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.header.AlternativeService;
import net.lecousin.framework.network.http.header.AlternativeServices;
import net.lecousin.framework.network.mime.MimeException;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.transfer.ChunkedTransfer;
import net.lecousin.framework.network.name.NameService;
import net.lecousin.framework.network.name.NameService.Resolution;
import net.lecousin.framework.text.ByteArrayStringIso8859Buffer;
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
		
		bodyProducer.thenStart("Prepare HTTP request", Task.getCurrentPriority(), (Task<Void, NoException> t) -> {
			ctx.setRequestBody(bodyProducer.getResult());
			sendRequest(ctx, connection);
			return null;
		}, ctx.getRequestSent());
	}
	
	private void sendRequest(
		HTTPClientRequestContext ctx,
		AsyncSupplier<HTTPClientConnection, IOException> connection
	) {
		HTTPRequest request = ctx.getRequest();
		Long size = ctx.getRequestBody().getValue1();
		Supplier<List<MimeHeader>> trailerSupplier = request.getTrailerHeadersSuppliers();
		
		if (size == null || trailerSupplier != null) {
			request.setHeader(MimeHeaders.TRANSFER_ENCODING, ChunkedTransfer.TRANSFER_NAME);
		} else if (size.longValue() > 0 || HTTPRequest.methodMayContainBody(request.getMethod())) {
			request.getHeaders().setContentLength(size.longValue());
		}
		
		connection.thenStart("Send HTTP request", Priority.NORMAL, (Task<Void, NoException> t) -> {
			if (ctx.getRequestSent().isDone())
				return null; // error or cancel
			connection.getResult().sendReserved(ctx).onDone(taken -> {
				if (!taken.booleanValue() && !retryToConnect(ctx))
					queue.addFirst(ctx);
			});
			return null;
		}, ctx.getRequestSent());
	}
	
	private AsyncSupplier<HTTPClientConnection, IOException> getConnection(HTTPClientRequestContext ctx) {
		AsyncSupplier<List<InetSocketAddress>, IOException> proxy = getProxy(ctx.getRequest());
		AsyncSupplier<HTTPClientConnection, IOException> result = new AsyncSupplier<>();
		proxy.onDone(list -> {
			if (list != null) {
				if (logger.debug()) logger.debug("Using proxy to connect to " + ctx.getRequest().getHostname());
				getConnection(list, true, ctx, result);
				return;
			}
			if (logger.debug()) logger.debug("Using direct connexion to " + ctx.getRequest().getHostname());
			AsyncSupplier<List<Resolution>, IOException> dns = NameService.resolveName(ctx.getRequest().getHostname());
			dns.thenStart("Get best connection for HTTP request", Priority.NORMAL, (Task<Void, NoException> t) -> {
				List<InetSocketAddress> addresses = new ArrayList<>(dns.getResult().size());
				int port = ctx.getRequest().getPort();
				for (Resolution r : dns.getResult())
					addresses.add(new InetSocketAddress(r.getIp(), port));
				if (logger.debug()) logger.debug("Host " + ctx.getRequest().getHostname() + " resolved into " + addresses);
				getConnection(addresses, false, ctx, result);
				return null;
			}, ctx.getRequestSent());
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
		@SuppressWarnings("unchecked")
		List<InetSocketAddress> remoteAddresses =
			(List<InetSocketAddress>)reservedFor.getAttribute(CLIENT_REQUEST_REMOTE_ADDRESSES_ATTRIBUTE);
		// try to reuse existing available connection
		for (InetSocketAddress addr : remoteAddresses) {
			HTTPClientConnectionManager manager = connectionManagers.get(addr);
			if (manager == null) continue;
			HTTPClientConnection connection = manager.reuseAvailableConnection(reservedFor);
			if (connection != null) {
				if (logger.debug()) logger.debug("Reuse available connection on " + addr + " for " + reservedFor);
				connectionUsed(manager, reservedFor);
				return connection;
			}
		}
		if (nbOpenConnections.get() < config.getLimits().getOpenConnections()) {
			// we can open a new connection, we prefer to do so
			for (InetSocketAddress addr : remoteAddresses) {
				HTTPClientConnectionManager manager = connectionManagers.get(addr);
				if (manager == null) {
					manager = new HTTPClientConnectionManager(this, addr, reservedFor.isThroughProxy(), config);
					connectionManagers.put(addr, manager);
					HTTPClientConnection connection = manager.createConnection(reservedFor);
					nbOpenConnections.inc();
					if (logger.debug()) logger.debug("New connection on " + addr + " for " + reservedFor);
					connectionUsed(manager, reservedFor);
					return connection;
				}
			}
			for (InetSocketAddress addr : remoteAddresses) {
				HTTPClientConnectionManager manager = connectionManagers.get(addr);
				HTTPClientConnection connection = manager.createConnection(reservedFor);
				if (connection != null) {
					nbOpenConnections.inc();
					if (logger.debug()) logger.debug("Add connection on " + addr + " for " + reservedFor);
					connectionUsed(manager, reservedFor);
					return connection;
				}
			}
		}
		// no available connexion, try to find a connection with a single pending request
		for (InetSocketAddress addr : remoteAddresses) {
			HTTPClientConnectionManager manager = connectionManagers.get(addr);
			if (manager == null) continue;
			HTTPClientConnection connection = manager.reuseConnectionIfPossible(reservedFor);
			if (connection != null) {
				if (logger.debug()) logger.debug("Reuse connection on " + addr + " for " + reservedFor);
				connectionUsed(manager, reservedFor);
				return connection;
			}
		}
		// if no open connection to one if the addresses, try to close an idle connection
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
						HTTPClientConnection connection = manager.createConnection(reservedFor);
						nbOpenConnections.inc();
						if (logger.debug()) logger.debug("New connection on " + addr + " for " + reservedFor);
						connectionUsed(manager, reservedFor);
						return connection;
					}
				}
			}
		}
		// nothing available
		if (logger.debug()) logger.debug("No connection available for " + reservedFor);
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
	
	private static void connectionUsed(HTTPClientConnectionManager manager, HTTPClientRequestContext ctx) {
		@SuppressWarnings("unchecked")
		List<InetSocketAddress> remoteAddresses =
			(List<InetSocketAddress>)ctx.getAttribute(CLIENT_REQUEST_REMOTE_ADDRESSES_ATTRIBUTE);
		remoteAddresses.remove(manager.getAddress());
	}
	
	private void dequeueRequest() {
		if (logger.debug()) logger.debug("Dequeue request");
		synchronized (connectionManagers) {
			if (queue.isEmpty())
				return;
			for (Iterator<HTTPClientRequestContext> it = queue.iterator(); it.hasNext(); ) {
				HTTPClientRequestContext c = it.next();
				if (retryToConnect(c)) {
					it.remove();
					return;
				}
			}
		}
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
		if (logger.debug()) logger.debug("Retry to connect to send " + ctx);
		HTTPClientConnection connection = tryToConnect(ctx);
		if (connection == null)
			return false;
		if (ctx.getRequestSent().isDone())
			return true; // error or cancel
		connection.sendReserved(ctx).onDone(taken -> {
			if (!taken.booleanValue())
				Task.cpu("HTTP client retry to connect", Priority.NORMAL, t -> {
					synchronized (connectionManagers) {
						if (!retryToConnect(ctx))
							queue.addFirst(ctx);
					}
					return null;
				}).start();
		});
		return true;
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
			if (!retryToConnect(ctx))
				queue.addFirst(ctx);
		}
	}

}
