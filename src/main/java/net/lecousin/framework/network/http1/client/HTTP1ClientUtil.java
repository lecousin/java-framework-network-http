package net.lecousin.framework.network.http1.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.concurrent.util.PartialAsyncConsumer;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientRequestFilter;
import net.lecousin.framework.network.http.client.HTTPClientResponse;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http1.HTTP1RequestCommandProducer;
import net.lecousin.framework.network.http1.HTTP1ResponseStatusConsumer;
import net.lecousin.framework.network.mime.entity.DefaultMimeEntityFactory;
import net.lecousin.framework.network.mime.entity.EmptyEntity;
import net.lecousin.framework.network.mime.entity.MimeEntity;
import net.lecousin.framework.network.mime.entity.MimeEntityFactory;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.transfer.ChunkedTransfer;
import net.lecousin.framework.network.mime.transfer.TransferEncodingFactory;
import net.lecousin.framework.text.ByteArrayStringIso8859Buffer;
import net.lecousin.framework.util.Pair;

/**
 * HTTP/1 utility methods to interact with a HTTP/1 server directly.
 */
public final class HTTP1ClientUtil {
	
	private HTTP1ClientUtil() {
		// no instance
	}

	/** Open a connection, possibly through a proxy, to the given HTTP/1 server. 
	 * @throws URISyntaxException if the path is relative
	 */
	public static Pair<? extends TCPClient, IAsync<IOException>> openConnection(
		String hostname, int port, boolean isSecure, String path, HTTPClientConfiguration config, Logger logger
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
		return openTunnelOnProxy((InetSocketAddress)proxy.address(), hostname, port, isSecure, config, logger);
	}
	
	/** Open a direct connection to a HTTP/1 server. */
	public static Pair<? extends TCPClient, IAsync<IOException>> openDirectConnection(
		InetSocketAddress serverAddress, String hostname, boolean isSecure, HTTPClientConfiguration config, Logger logger
	) {
		if (isSecure)
			return openDirectSSLConnection(serverAddress, hostname, config, logger);
		return openDirectConnection(serverAddress, config, logger);
	}
	
	/** Open a direct connection to a HTTP/1 server. */
	public static Pair<TCPClient, IAsync<IOException>> openDirectConnection(
		InetSocketAddress serverAddress, HTTPClientConfiguration config, Logger logger
	) {
		if (logger.debug())
			logger.debug("Open direct clear connection to HTTP server " + serverAddress);
		TCPClient client = new TCPClient();
		Async<IOException> connect = client.connect(
			serverAddress, config.getTimeouts().getConnection(), config.getSocketOptionsArray());
		return new Pair<>(client, connect);
	}
	
	/** Open a direct connection to a HTTP/1 server. */
	public static Pair<SSLClient, IAsync<IOException>> openDirectSSLConnection(
		InetSocketAddress serverAddress, String hostname, HTTPClientConfiguration config, Logger logger
	) {
		if (logger.debug())
			logger.debug("Open direct SSL connection to HTTPS server " + serverAddress);
		SSLClient client = new SSLClient(config.getSSLContext());
		client.setHostNames(hostname);
		Async<IOException> connect = client.connect(
			serverAddress, config.getTimeouts().getConnection(), config.getSocketOptionsArray());
		return new Pair<>(client, connect);
	}
	
	/** Open a tunnel on HTTP/1 proxy server. */
	public static Pair<TCPClient, IAsync<IOException>> openTunnelOnProxy(
		InetSocketAddress proxyAddress,
		String targetHostname, int targetPort, boolean isSecure,
		HTTPClientConfiguration config, Logger logger
	) {
		if (logger.debug())
			logger.debug("Open connection to HTTP" + (isSecure ? "S" : "") + " server " + targetHostname + ":" + targetPort
				+ " through proxy " + proxyAddress);
		
		Pair<TCPClient, IAsync<IOException>> proxyConnection = openDirectConnection(proxyAddress, config, logger);
		
		// prepare the CONNECT request
		String host = targetHostname + ":" + targetPort;
		HTTPRequest connectRequest = new HTTPRequest()
			.setMethod(HTTPRequest.METHOD_CONNECT).setEncodedPath(new ByteArrayStringIso8859Buffer(host));
		connectRequest.addHeader(HTTPConstants.Headers.Request.HOST, host);
		ByteArrayStringIso8859Buffer headers = new ByteArrayStringIso8859Buffer();
		headers.setNewArrayStringCapacity(512);
		HTTP1RequestCommandProducer.generate(connectRequest, headers);
		headers.append("\r\n");
		connectRequest.getHeaders().generateString(headers);
		ByteBuffer[] buffers = headers.asByteBuffers();
		
		// prepare SSL client
		TCPClient client;
		if (isSecure) {
			@SuppressWarnings("resource")
			SSLClient sslClient = new SSLClient(config.getSSLContext());
			sslClient.setHostNames(targetHostname);
			client = sslClient;
		} else {
			client = proxyConnection.getValue1();
		}
		
		Async<IOException> connect = new Async<>();
		
		proxyConnection.getValue2().onDone(() ->
		proxyConnection.getValue1().asConsumer(2, config.getTimeouts().getSend()).push(Arrays.asList(buffers)).onDone(() -> {
			// once headers are sent, receive the response status line
			HTTPResponse response = new HTTPResponse();
			proxyConnection.getValue1().getReceiver().consume(
				new HTTP1ResponseStatusConsumer(response)
				.convert(ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), IO::error),
				4096, config.getTimeouts().getReceive())
			.onDone(() -> {
				// status line received
				if (!response.isSuccess()) {
					connect.error(new HTTPResponseError(response));
					return;
				}
				// read headers
				MimeHeaders responseHeaders = new MimeHeaders();
				response.setHeaders(responseHeaders);
				proxyConnection.getValue1().getReceiver().consume(
					responseHeaders.createConsumer(config.getLimits().getHeadersLength())
					.convert(ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), IO::error),
					4096, config.getTimeouts().getReceive())
				.onDone(() -> {
					// headers received
					// tunnel connection established
					if (isSecure) {
						// replace connection of SSLClient by the tunnel
						if (logger.debug())
							logger.debug("Tunnel open through proxy " + proxyAddress + ", start SSL handshake");
						((SSLClient)client).tunnelConnected(proxyConnection.getValue1(), connect,
							config.getTimeouts().getReceive());
					} else {
						connect.unblock();
					}
				}, connect);
			}, connect);
		}, connect), connect);
		return new Pair<>(client, connect);
	}
	
	/**
	 * Send an HTTP request, with an optional body.
	 */
	public static Async<IOException> send(
		TCPClient client, IAsync<IOException> connect,
		HTTPClientRequest request, HTTPClientResponse response,
		HTTPClientConfiguration config, Logger logger
	) {
		// call filters
		for (HTTPClientRequestFilter filter : config.getFilters())
			filter.filter(request, response);

		Async<IOException> result = new Async<>();
		
		// prepare body
		MimeEntity entity = request.getEntity();
		AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> bodyProducer;
		if (entity != null)
			bodyProducer = entity.createBodyProducer();
		else
			bodyProducer = new AsyncSupplier<>(new Pair<>(Long.valueOf(0), new AsyncProducer.Empty<>()), null);
		
		if (bodyProducer.isDone()) {
			if (bodyProducer.hasError())
				result.error(bodyProducer.getError());
			else
				sendRequest(client, connect, request, bodyProducer, config, logger, result);
		} else {
			bodyProducer.thenStart("Send HTTP request", Task.getCurrentPriority(),
				() -> sendRequest(client, connect, request, bodyProducer, config, logger, result), result);
		}
		return result;
	}
	
	private static void sendRequest(
		TCPClient client, IAsync<IOException> connect,
		HTTPRequest request,
		AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> bodyProducer,
		HTTPClientConfiguration config, Logger logger,
		Async<IOException> result
	) {
		Long size = bodyProducer.getResult().getValue1();
		Supplier<List<MimeHeader>> trailerSupplier = request.getTrailerHeadersSuppliers();
		
		if (size == null || trailerSupplier != null) {
			request.setHeader(MimeHeaders.TRANSFER_ENCODING, ChunkedTransfer.TRANSFER_NAME);
		} else if (size.longValue() > 0 || HTTPRequest.methodMayContainBody(request.getMethod())) {
			request.getHeaders().setContentLength(size.longValue());
		}
		
		if (logger.trace())
			logger.trace("Send HTTP/1 request to " + client + ": "
				+ HTTP1RequestCommandProducer.generateString(request) + "\n"
				+ request.getHeaders().generateString(1024).asString());

		// prepare headers
		ByteArrayStringIso8859Buffer headers = new ByteArrayStringIso8859Buffer();
		headers.setNewArrayStringCapacity(4096);
		HTTP1RequestCommandProducer.generate(request, headers);
		headers.append("\r\n");
		request.getHeaders().generateString(headers);
		ByteBuffer[] buffers = headers.asByteBuffers();
		
		AsyncConsumer<ByteBuffer, IOException> clientConsumer = client.asConsumer(3, config.getTimeouts().getSend());
		
		connect.onDone(() -> {
			IAsync<IOException> sendHeaders = clientConsumer.push(Arrays.asList(buffers));
			if (size != null && size.longValue() == 0) {
				sendHeaders.onDone(result);
				return;
			}
			sendHeaders.onError(result::error);
			AsyncConsumer<ByteBuffer, IOException> consumer = 
				size == null || trailerSupplier != null ? new ChunkedTransfer.Sender(clientConsumer, trailerSupplier)
					: clientConsumer;
			Async<IOException> sendBody = bodyProducer.getResult().getValue2()
				.toConsumer(consumer, "Send HTTP request body", Task.getCurrentPriority());
			sendBody.onDone(result);
		}, result);
	}
	
	
	/**
	 * Receive the response from the server.
	 * 
	 * @param onStatusReceived (may be null) called once the status line has been received.
	 *        If it returns null, the process continues. If it returns a boolean the process is stopped and the boolean value indicates
	 *        if the client must be closed or not.
	 * @param onHeadersReceived (may be null) called once the headers have been received.
	 *        If it returns null, the process continues. If it returns a boolean the process is stopped and the boolean value indicates
	 *        if the client must be closed or not.
	 * @param entityFactory called once the headers have been received to create an entity that will receive the response body.
	 *        If null, DefaultMimeEntityFactory.getInstance() is used.
	 */
	@SuppressWarnings("java:S4276") // we want a Boolean not a boolean
	public static void receiveResponse(
		TCPClient client,
		HTTPClientResponse response,
		Function<HTTPClientResponse, Boolean> onStatusReceived,
		Function<HTTPClientResponse, Boolean> onHeadersReceived,
		MimeEntityFactory entityFactory,
		HTTPClientConfiguration config,
		Logger logger
	) {
		PartialAsyncConsumer<ByteBuffer, IOException> statusLineConsumer = new HTTP1ResponseStatusConsumer(response).convert(
			ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), IO::error);
		IAsync<IOException> receiveStatusLine = client.getReceiver().consume(statusLineConsumer, 4096, config.getTimeouts().getReceive());
		response.setHeaders(new MimeHeaders());
		MimeEntityFactory ef = entityFactory != null ? entityFactory : DefaultMimeEntityFactory.getInstance();
		if (receiveStatusLine.isDone())
			reveiceResponseHeaders(client, response, receiveStatusLine, ef, onStatusReceived, onHeadersReceived, config, logger);
		else
			receiveStatusLine.thenStart("Receive HTTP response", Task.getCurrentPriority(),
				() -> reveiceResponseHeaders(client, response, receiveStatusLine, ef,
					onStatusReceived, onHeadersReceived, config, logger),
				true);
	}
	
	@SuppressWarnings({
		"java:S4276", // we want a Boolean not a boolean
		"java:S107" // number of parameters
	})
	private static void reveiceResponseHeaders(
		TCPClient client, HTTPClientResponse response,
		IAsync<IOException> receiveStatusLine,
		MimeEntityFactory entityFactory,
		Function<HTTPClientResponse, Boolean> onStatusReceived,
		Function<HTTPClientResponse, Boolean> onHeadersReceived,
		HTTPClientConfiguration config,
		Logger logger
	) {
		if (receiveStatusLine.forwardIfNotSuccessful(response.getHeadersReceived())) {
			client.close();
			return;
		}
		
		if (logger.trace())
			logger.trace("Status line received from " + client + ": " + response.getStatusCode() + " " + response.getStatusMessage());
		
		if (onStatusReceived != null) {
			Boolean close = onStatusReceived.apply(response);
			if (close != null) {
				if (close.booleanValue())
					client.close();
				return;
			}
		}
		PartialAsyncConsumer<ByteBuffer, IOException> headersConsumer = response.getHeaders()
			.createConsumer(config.getLimits().getHeadersLength()).convert(
					ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), IO::error);
		IAsync<IOException> receiveHeaders = client.getReceiver().consume(headersConsumer, 4096, config.getTimeouts().getReceive());
		if (receiveHeaders.isDone())
			reveiceResponseBody(client, response, receiveHeaders, entityFactory, onHeadersReceived, config, logger);
		else
			receiveHeaders.thenStart("Receive HTTP response", Task.getCurrentPriority(),
				() -> reveiceResponseBody(client, response, receiveHeaders, entityFactory, onHeadersReceived, config, logger), true);
	}

	@SuppressWarnings({
		"java:S4276", // we want a Boolean not a boolean
		"java:S107" // number of parameters
	})
	private static void reveiceResponseBody(
		TCPClient client, HTTPClientResponse response,
		IAsync<IOException> receiveHeaders,
		MimeEntityFactory entityFactory,
		Function<HTTPClientResponse, Boolean> onHeadersReceived,
		HTTPClientConfiguration config, Logger logger
	) {
		if (receiveHeaders.forwardIfNotSuccessful(response.getHeadersReceived())) {
			client.close();
			return;
		}
		
		if (logger.trace())
			logger.trace("Headers received from " + client + ":\n" + response.getHeaders().generateString(1024).asString());
		
		if (onHeadersReceived != null) {
			Boolean close = onHeadersReceived.apply(response);
			if (close != null) {
				if (close.booleanValue())
					client.close();
				return;
			}
		}
		
		response.getHeadersReceived().unblock();

		Long size = response.getHeaders().getContentLength();
		PartialAsyncConsumer<ByteBuffer, IOException> transfer;
		try {
			if (response.isBodyExpected()) {
				MimeEntity entity = entityFactory.create(null, response.getHeaders());
				transfer = TransferEncodingFactory.create(response.getHeaders(),
					entity.createConsumer(response.getHeaders().getContentLength()));
				response.setEntity(entity);
			} else {
				response.setEntity(new EmptyEntity(null, response.getHeaders()));
				response.getBodyReceived().unblock();
				response.getTrailersReceived().unblock();
				return;
			}
		} catch (Exception e) {
			client.close();
			response.getBodyReceived().error(IO.error(e));
			return;
		}
		int bufferSize;
		if (size == null)
			bufferSize = 8192;
		else if (size.longValue() <= 1024)
			bufferSize = 1024;
		else if (size.longValue() <= 64 * 1024)
			bufferSize = size.intValue();
		else
			bufferSize = 64 * 1024;
		IAsync<IOException> receiveBody = client.getReceiver().consume(transfer, bufferSize, config.getTimeouts().getReceive());
		receiveBody.onDone(() -> {
			response.getBodyReceived().unblock();
			response.getTrailersReceived().unblock();
		}, response.getBodyReceived());
	}
	
	/** Send request and receive response. */
	public static HTTPClientResponse sendAndReceive(
		HTTPClientRequest request,
		int maxRedirections,
		MimeEntityFactory entityFactory,
		HTTPClientConfiguration config
	) {
		HTTPClientResponse response = new HTTPClientResponse();
		sendAndReceive(request, response, maxRedirections, null, null, entityFactory, config,
			LCCore.getApplication().getLoggerFactory().getLogger(HTTP1ClientUtil.class));
		return response;
	}
	
	/** Send request and receive response. */
	public static HTTPClientResponse sendAndReceive(
		HTTPClientRequest request,
		int maxRedirections,
		Function<HTTPClientResponse, Boolean> onStatusReceived,
		Function<HTTPClientResponse, Boolean> onHeadersReceived,
		MimeEntityFactory entityFactory,
		HTTPClientConfiguration config,
		Logger logger
	) {
		HTTPClientResponse response = new HTTPClientResponse();
		sendAndReceive(request, response, maxRedirections, onStatusReceived, onHeadersReceived, entityFactory, config, logger);
		return response;
	}
	
	/** Send request and receive response. */
	@SuppressWarnings("java:S107") // number of parameters
	public static void sendAndReceive(
		HTTPClientRequest request,
		HTTPClientResponse response,
		int maxRedirections,
		Function<HTTPClientResponse, Boolean> onStatusReceived,
		Function<HTTPClientResponse, Boolean> onHeadersReceived,
		MimeEntityFactory entityFactory,
		HTTPClientConfiguration config,
		Logger logger
	) {
		Pair<? extends TCPClient, IAsync<IOException>> client;
		try {
			client = openConnection(request.getHostname(), request.getPort(), request.isSecure(), request.getEncodedPath().asString(),
				config, logger);
		} catch (URISyntaxException e) {
			response.getHeadersReceived().error(IO.error(e));
			return;
		}
		sendAndReceive(client.getValue1(), client.getValue2(), request, response, maxRedirections,
			onStatusReceived, onHeadersReceived, entityFactory, config, logger);
		response.getTrailersReceived().onDone(client.getValue1()::close);
	}

	/** Send request and receive response. */
	@SuppressWarnings("java:S107") // number of parameters
	public static void sendAndReceive(
		TCPClient client, IAsync<IOException> connect,
		HTTPClientRequest request,
		HTTPClientResponse response,
		int maxRedirections,
		Function<HTTPClientResponse, Boolean> onStatusReceived,
		Function<HTTPClientResponse, Boolean> onHeadersReceived,
		MimeEntityFactory entityFactory,
		HTTPClientConfiguration config,
		Logger logger
	) {
		send(client, connect, request, response, config, logger).thenStart("Receive HTTP/1 response", Task.getCurrentPriority(), () ->
			receiveResponse(client, response, onStatusReceived, resp -> {
				if (handleRedirection(client, request, response, maxRedirections, config, logger,
					newClient -> sendAndReceive(
						newClient.getValue1(), newClient.getValue2(),
						request, response, maxRedirections - 1,
						onStatusReceived, onHeadersReceived, entityFactory, config, logger)))
						return Boolean.FALSE;
					if (onHeadersReceived != null)
						return onHeadersReceived.apply(response);
					return null;
			}, entityFactory, config, logger), response.getHeadersReceived());
	}
	
	@SuppressWarnings({"java:S1141", "java:S107"}) // nested try and number of parameters
	private static boolean handleRedirection(
		TCPClient client,
		HTTPClientRequest request, HTTPClientResponse response, int maxRedirect,
		HTTPClientConfiguration config,
		Logger logger,
		Consumer<Pair<? extends TCPClient, IAsync<IOException>>> doRedirection
	) {
		if (maxRedirect <= 0)
			return false;
		if (!HTTPResponse.isRedirectionStatusCode(response.getStatusCode()))
			return false;
		String location = response.getHeaders().getFirstRawValue(HTTPConstants.Headers.Response.LOCATION);
		if (location == null)
			return false;
		try {
			Long size = response.getHeaders().getContentLength();
			Async<IOException> skipBody;
			if (size != null && size.longValue() > 0)
				skipBody = client.getReceiver().skipBytes(size.intValue(), config.getTimeouts().getReceive());
			else
				skipBody = new Async<>(true);

			URI u = new URI(location);
			if (u.getHost() == null) {
				// relative
				u = request.generateURI().resolve(u);
			}
			request.setURI(u);
			if (isCompatible(u, client, request.getHostname(), request.getPort())) {
				skipBody.onDone(() -> doRedirection.accept(new Pair<>(client, new Async<>(true))));
				return true;
			}
			Pair<? extends TCPClient, IAsync<IOException>> newClient =
				openConnection(u.getHost(), u.getPort(), HTTPConstants.HTTPS_SCHEME.equalsIgnoreCase(u.getScheme()), u.getPath(),
					config, logger);
			skipBody.onDone(() -> doRedirection.accept(newClient));
			response.getTrailersReceived().onDone(newClient.getValue1()::close);
		} catch (URISyntaxException e) {
			response.getHeadersReceived().error(new IOException("Invalid redirect location: " + location, e));
		}
		return true;
	}
	
	/** Return true if the protocol, the hostname and the port are compatible with this client. */
	private static boolean isCompatible(URI uri, TCPClient client, String hostname, int port) {
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
}
