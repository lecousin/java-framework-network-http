package net.lecousin.framework.network.http.client;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
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
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.io.out2in.OutputToInput;
import net.lecousin.framework.io.util.EmptyReadable;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.mutable.Mutable;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http.exception.UnsupportedHTTPProtocolException;
import net.lecousin.framework.network.http1.HTTP1RequestCommandProducer;
import net.lecousin.framework.network.http1.HTTP1ResponseStatusConsumer;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.entity.BinaryFileEntity;
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

/** HTTP client. */
public class HTTPClient implements Closeable {
	
	/** Return the default HTTPS port for an SSLClient else the default HTTP port. */
	public static int getDefaultPort(TCPClient client) {
		return client instanceof SSLClient ? HTTPConstants.DEFAULT_HTTPS_PORT : HTTPConstants.DEFAULT_HTTP_PORT;
	}
	
	/** Constructor. */
	public HTTPClient(TCPClient client, String hostname, int port, HTTPClientConfiguration config) {
		this.client = client;
		this.hostname = hostname;
		this.port = port;
		this.config = config;
		this.logger = LCCore.getApplication().getLoggerFactory().getLogger(HTTPClient.class);
	}

	/** Constructor. */
	public HTTPClient(TCPClient client, String hostname, HTTPClientConfiguration config) {
		this(client, hostname, getDefaultPort(client), config);
	}
	
	/** Create a client for the given URI. */
	public static HTTPClient create(URI uri, HTTPClientConfiguration config) throws UnsupportedHTTPProtocolException, GeneralSecurityException {
		String protocol = uri.getScheme();
		if (protocol == null) throw new UnsupportedHTTPProtocolException(null);
		protocol = protocol.toLowerCase();
		if (!"http".equals(protocol) && !"https".equals(protocol))
			throw new UnsupportedHTTPProtocolException(protocol);

		String hostname = uri.getHost();
		int port = uri.getPort();
		if (port <= 0) {
			if ("http".equals(protocol))
				port = HTTPConstants.DEFAULT_HTTP_PORT;
			else
				port = HTTPConstants.DEFAULT_HTTPS_PORT;
		}
		
		TCPClient client;
		if ("http".equals(protocol))
			client = new TCPClient();
		else {
			client = config.getSSLContext() != null ? new SSLClient(config.getSSLContext()) : new SSLClient();
			((SSLClient)client).setHostNames(hostname);
		}
		
		return new HTTPClient(client, hostname, port, config);
	}
	
	/** Create a client for the given URI. */
	public static HTTPClient create(URI uri) throws UnsupportedHTTPProtocolException, GeneralSecurityException {
		return create(uri, HTTPClientConfiguration.defaultConfiguration);
	}
	
	/** Create a client for the given URL. */
	public static HTTPClient create(URL url) throws UnsupportedHTTPProtocolException, GeneralSecurityException, URISyntaxException {
		return create(url.toURI());
	}
	
	/** Create a client to the given address. If this config is null, the default is used. */
	public static HTTPClient create(InetSocketAddress address, boolean https, HTTPClientConfiguration config) throws GeneralSecurityException {
		String hostname = address.getHostString();
		int port = address.getPort();
		if (config == null) config = HTTPClientConfiguration.defaultConfiguration;
		
		TCPClient client;
		if (!https)
			client = new TCPClient();
		else {
			client = config.getSSLContext() != null ? new SSLClient(config.getSSLContext()) : new SSLClient();
			((SSLClient)client).setHostNames(hostname);
		}
		
		return new HTTPClient(client, hostname, port, config);
	}

	/** Create a client to the given address using the default configuration. */
	public static HTTPClient create(InetSocketAddress address, boolean https) throws GeneralSecurityException {
		return create(address, https, null);
	}
	
	protected TCPClient client;
	protected String hostname;
	protected int port;
	protected HTTPClientConfiguration config;
	private Logger logger;
	
	public TCPClient getTCPClient() {
		return client;
	}
	
	/** Return the hostname given before connection occurs. */
	public String getRequestedHostname() {
		return hostname;
	}
	
	/** Return the port given before connection occurs. */
	public int getRequestedPort() {
		return port;
	}
	
	/** Generate the URL (without query string) for the given request. */
	public String generateURL(HTTPRequest request) {
		StringBuilder url = new StringBuilder(128);
		if (client instanceof SSLClient)
			url.append("https://");
		else
			url.append("http://");
		url.append(hostname);
		if (client instanceof SSLClient) {
			if (port != HTTPConstants.DEFAULT_HTTPS_PORT)
				url.append(':').append(port);
		} else if (port != HTTPConstants.DEFAULT_HTTP_PORT) {
			url.append(':').append(port);
		}
		url.append(request.getEncodedPath());
		return url.toString();
	}
	
	/** Return true if the protocol, the hostname and the port are compatible with this client. */
	public boolean isCompatible(URI uri) {
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

		if (!this.hostname.equals(uri.getHost()))
			return false;
		
		int p = uri.getPort();
		if (p <= 0) {
			if (client instanceof SSLClient)
				p = HTTPConstants.DEFAULT_HTTPS_PORT;
			else
				p = HTTPConstants.DEFAULT_HTTP_PORT;
		}
		return p == this.port;
	}
	
	/**
	 * Send an HTTP request, with an optional body.<br/>
	 * If the client is not yet connected, first a connection is established to the server or the
	 * proxy returned by the ProxySelector configured in the HTTPClientConfiguration.<br/>
	 * While the connection is established, the request is prepared:<br/>
	 * Interceptors configured in the HTTPClientConfiguration are first called to modify the request.<br/>
	 * If a body is given (not null):<ul>
	 * <li>If it implements IO.KnownSize, a Content-Length is set on the request header, else
	 * 	the Transfer-Encoding header is set to chunked.</li>
	 * <li>If it implements IO.Readable.Buffered, the method readNextBufferAsync will be used to send
	 * 	buffer by buffer as soon as they are ready. Else a default buffer size is used: if the total size
	 *  is known, the buffer size is set to 1MB for a body larger than 8MB, to 512KB for a body larger
	 *  than 1MB, 64KB for a body larger than 128KB, 16KB for a body larger than 32KB, or a single buffer
	 *  for a size less than 32KB.
	 * </ul>
	 */
	public Async<IOException> sendRequest(HTTPRequest request) {
		// connection
		Async<IOException> connect = connect(request);
		if (connect.isDone() && !connect.isSuccessful()) return connect;
		Async<IOException> result = new Async<>();
		
		// prepare to send data while connecting

		// call interceptors
		for (HTTPRequestInterceptor interceptor : config.getInterceptors())
			interceptor.intercept(request, hostname, port);

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
				sendRequest(request, bodyProducer, connect, result);
		} else {
			bodyProducer.thenStart("Send HTTP request", Task.getCurrentPriority(),
				() -> sendRequest(request, bodyProducer, connect, result), result);
		}
		return result;
	}
	
	private void sendRequest(
		HTTPRequest request,
		AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> bodyProducer, Async<IOException> connect,
		Async<IOException> result
	) {
		Long size = bodyProducer.getResult().getValue1();
		Supplier<List<MimeHeader>> trailerSupplier = request.getTrailerHeadersSuppliers();
		
		if (size == null || trailerSupplier != null) {
			request.setHeader(MimeHeaders.TRANSFER_ENCODING, ChunkedTransfer.TRANSFER_NAME);
		} else if (size.longValue() > 0 || HTTPRequest.methodMayContainBody(request.getMethod())) {
			request.getHeaders().setContentLength(size.longValue());
		}

		// prepare headers
		ByteArrayStringIso8859Buffer headers = new ByteArrayStringIso8859Buffer();
		headers.setNewArrayStringCapacity(4096);
		HTTP1RequestCommandProducer.generate(request, headers);
		headers.append("\r\n");
		request.getHeaders().generateString(headers);
		ByteBuffer[] buffers = headers.asByteBuffers();
		
		AsyncConsumer<ByteBuffer, IOException> clientConsumer = client.asConsumer(3, config.getSendTimeout());
		
		connect.onDone(() -> {
			if (logger.debug()) {
				logger.debug(client.toString() + " connected to server, send headers: " + request);
			}
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
	
	/** Connect to the remote server before to send the given request. */
	private Async<IOException> connect(HTTPRequest request) {
		if (!client.isClosed())
			return new Async<>(true);
		// get proxy if any
		ProxySelector proxySelector = config.getProxySelector();
		Proxy proxy = null;
		if (proxySelector != null) {
			String url = generateURL(request);
			URI uri = null;
			try {
				uri = new URI(url);
				List<Proxy> proxies = proxySelector.select(uri);
				for (Proxy p : proxies) {
					if (Proxy.Type.HTTP.equals(p.type())) {
						proxy = p;
						break;
					}
				}
			} catch (Exception e) {
				// ignore
			}
			if (proxy != null) {
				InetSocketAddress inet = (InetSocketAddress)proxy.address();
				//inet = new InetSocketAddress(inet.getHostName(), inet.getPort());
				if (client instanceof SSLClient) {
					// we have to create a HTTP tunnel with the proxy
					return createHTTPTunnel(inet);
				}
				if (logger.debug()) logger.debug("Connecting to proxy " + inet);
				Async<IOException> connect = client.connect(inet, config.getConnectionTimeout(), config.getSocketOptionsArray());
				request.setEncodedPath(new ByteArrayStringIso8859Buffer(url));
				return connect;
			}
		}
		// direct connection
		if (logger.debug()) logger.debug("Connecting to server " + hostname + ":" + port);
		return client.connect(
			new InetSocketAddress(hostname, port), config.getConnectionTimeout(), config.getSocketOptionsArray());
	}
	
	private Async<IOException> createHTTPTunnel(InetSocketAddress inet) {
		if (logger.debug()) logger.debug("Establishing a tunnel connection with proxy " + inet);
		@SuppressWarnings("squid:S2095") // it is closed
		TCPClient tunnelClient = new TCPClient();
		Async<IOException> tunnelConnect =
			tunnelClient.connect(inet, config.getConnectionTimeout(), config.getSocketOptionsArray());
		Async<IOException> connect = new Async<>();
		// prepare the CONNECT request
		HTTPRequest connectRequest = new HTTPRequest()
			.setMethod(HTTPRequest.METHOD_CONNECT).setDecodedPath(hostname + ":" + port);
		connectRequest.addHeader(HTTPConstants.Headers.Request.HOST, hostname + ":" + port);
		ByteArrayStringIso8859Buffer headers = new ByteArrayStringIso8859Buffer();
		headers.setNewArrayStringCapacity(4096);
		HTTP1RequestCommandProducer.generate(connectRequest, headers);
		headers.append("\r\n");
		connectRequest.getHeaders().generateString(headers);
		ByteBuffer[] buffers = headers.asByteBuffers();
		// once connected, send the headers
		tunnelConnect.onDone(() -> tunnelClient.asConsumer(2, config.getSendTimeout()).push(Arrays.asList(buffers)).onDone(() -> {
			// once headers are sent, receive the response status line
			HTTPResponse response = new HTTPResponse();
			tunnelClient.getReceiver().consume(
				new HTTP1ResponseStatusConsumer(response)
				.convert(ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), IO::error),
				4096, config.getReceiveTimeout()).onDone(() -> {
				// status line received
				if (!response.isSuccess()) {
					connect.error(new HTTPResponseError(response));
					return;
				}
				// read headers
				MimeHeaders responseHeaders = new MimeHeaders();
				response.setHeaders(responseHeaders);
				tunnelClient.getReceiver().consume(
					responseHeaders.createConsumer(config.getMaximumResponseHeadersLength())
					.convert(ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), IO::error),
					4096, config.getReceiveTimeout()).onDone(
						// headers received
						// tunnel connection established
						// replace connection of SSLClient by the tunnel
						() -> ((SSLClient)client).tunnelConnected(tunnelClient, connect, config.getReceiveTimeout()),
					connect);
			}, connect);
		}, connect), connect);
		connect.onErrorOrCancel(tunnelClient::close);
		return connect;
	}
	
	/** Receive the response from the server.
	 * The response is returned as soon as the headers have been received, and the response entity
	 * is set to a BinaryEntity with an OutputToInput so the body can be read while it is received.
	 */
	public AsyncSupplier<HTTPResponse, IOException> receiveResponseHeadersThenBodyAsBinary(int maxBodyInMemory) {
		AsyncSupplier<HTTPResponse, IOException> result = new AsyncSupplier<>();
		Mutable<HTTPResponse> resp = new Mutable<>(null);
		AsyncSupplier<HTTPResponse, IOException> receive = receiveResponse(null, response -> {
			resp.set(response);
			return null;
		}, (parent, headers) -> {
			BinaryEntity entity = new BinaryEntity(parent, headers);
			IOInMemoryOrFile io = new IOInMemoryOrFile(maxBodyInMemory, Task.getCurrentPriority(), "BinaryEntity");
			entity.setContent(new OutputToInput(io, io.getSourceDescription()));
			resp.get().setEntity(entity);
			result.unblockSuccess(resp.get());
			return entity;
		});
		receive.onError(error -> {
			if (resp.get() != null && resp.get().getEntity() != null)
				((OutputToInput)((BinaryEntity)resp.get().getEntity()).getContent()).signalErrorBeforeEndOfData(error);
			result.error(error);
		});
		receive.onSuccess(() -> {
			if (!result.isDone()) {
				// empty body, entityFactory was not called
				BinaryEntity entity = new BinaryEntity(null, resp.get().getHeaders());
				entity.setContent(new EmptyReadable("empty body", Task.Priority.NORMAL));
				resp.get().setEntity(entity);
				result.unblockSuccess(resp.get());
			}
		});
		return result;
	}
	
	/** Receive the response from the server, with a body as a BinaryEntity. */
	public AsyncSupplier<HTTPResponse, IOException> receiveResponseAsBinary() {
		return receiveResponse(null, null, BinaryEntity::new);
	}
	
	/** Receive the response from the server. */
	public AsyncSupplier<HTTPResponse, IOException> receiveResponse() {
		return receiveResponse(null, null, DefaultMimeEntityFactory.getInstance());
	}

	/**
	 * Receive the response from the server.
	 * 
	 * @param entityFactory called once the headers have been received to create an entity that will receive the response body.
	 * @return the response or an error
	 */
	public AsyncSupplier<HTTPResponse, IOException> receiveResponse(MimeEntityFactory entityFactory) {
		return receiveResponse(null, null, entityFactory);
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
	 * @return the response or an error
	 */
	@SuppressWarnings("java:S4276") // we want a Boolean not a boolean
	public AsyncSupplier<HTTPResponse, IOException> receiveResponse(
		Function<HTTPResponse, Boolean> onStatusReceived,
		Function<HTTPResponse, Boolean> onHeadersReceived,
		MimeEntityFactory entityFactory
	) {
		if (logger.trace())
			logger.trace("Start receiving HTTP response from " + client);
		HTTPResponse response = new HTTPResponse();
		PartialAsyncConsumer<ByteBuffer, IOException> statusLineConsumer = new HTTP1ResponseStatusConsumer(response).convert(
			ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), IO::error);
		IAsync<IOException> receiveStatusLine = client.getReceiver().consume(statusLineConsumer, 4096, config.getReceiveTimeout());
		AsyncSupplier<HTTPResponse, IOException> result = new AsyncSupplier<>();
		response.setHeaders(new MimeHeaders());
		MimeEntityFactory ef = entityFactory != null ? entityFactory : DefaultMimeEntityFactory.getInstance();
		if (receiveStatusLine.isDone())
			reveiceResponseHeaders(receiveStatusLine, response, result, ef, onStatusReceived, onHeadersReceived);
		else
			receiveStatusLine.thenStart("Receive HTTP response", Task.getCurrentPriority(),
				() -> reveiceResponseHeaders(receiveStatusLine, response, result, ef, onStatusReceived, onHeadersReceived),
				true);
		return result;
	}
	
	@SuppressWarnings("java:S4276") // we want a Boolean not a boolean
	private void reveiceResponseHeaders(
		IAsync<IOException> receiveStatusLine, HTTPResponse response, AsyncSupplier<HTTPResponse, IOException> result,
		MimeEntityFactory entityFactory, Function<HTTPResponse, Boolean> onStatusReceived,
		Function<HTTPResponse, Boolean> onHeadersReceived
	) {
		if (receiveStatusLine.forwardIfNotSuccessful(result)) {
			close();
			return;
		}
		if (logger.trace())
			logger.trace("Received HTTP status line from " + client + ": " + response.getProtocolVersion() + " "
				+ response.getStatusCode() + " " + response.getStatusMessage());
		if (onStatusReceived != null) {
			Boolean close = onStatusReceived.apply(response);
			if (close != null) {
				if (close.booleanValue())
					close();
				return;
			}
		}
		PartialAsyncConsumer<ByteBuffer, IOException> headersConsumer = response.getHeaders()
			.createConsumer(config.getMaximumResponseHeadersLength()).convert(
					ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), IO::error);
		IAsync<IOException> receiveHeaders = client.getReceiver().consume(headersConsumer, 4096, config.getReceiveTimeout());
		if (receiveHeaders.isDone())
			reveiceResponseBody(receiveHeaders, response, result, entityFactory, onHeadersReceived);
		else
			receiveHeaders.thenStart("Receive HTTP response", Task.getCurrentPriority(),
				() -> reveiceResponseBody(receiveHeaders, response, result, entityFactory, onHeadersReceived), true);
	}

	@SuppressWarnings("java:S4276") // we want a Boolean not a boolean
	private void reveiceResponseBody(
		IAsync<IOException> receiveHeaders, HTTPResponse response, AsyncSupplier<HTTPResponse, IOException> result,
		MimeEntityFactory entityFactory, Function<HTTPResponse, Boolean> onHeadersReceived
	) {
		if (receiveHeaders.forwardIfNotSuccessful(result)) {
			close();
			return;
		}
		if (logger.trace())
			logger.trace("Received HTTP response headers from " + client + ":\r\n"
				+ response.getHeaders().generateString(1024).toString());
		if (onHeadersReceived != null) {
			Boolean close = onHeadersReceived.apply(response);
			if (close != null) {
				if (close.booleanValue())
					close();
				return;
			}
		}
		Long size = response.getHeaders().getContentLength();
		PartialAsyncConsumer<ByteBuffer, IOException> transfer;
		try {
			if (response.isBodyExpected()) {
				MimeEntity entity = entityFactory.create(null, response.getHeaders());
				transfer = TransferEncodingFactory.create(response.getHeaders(),
					entity.createConsumer(response.getHeaders().getContentLength()));
				response.setEntity(entity);
			} else {
				if (logger.trace())
					logger.trace("No body expected: end of HTTP response from " + client);
				response.setEntity(new EmptyEntity(null, response.getHeaders()));
				result.unblockSuccess(response);
				return;
			}
		} catch (Exception e) {
			close();
			result.error(IO.error(e));
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
		if (logger.trace())
			logger.trace("Start receiving HTTP response body from " + client + " with transfer: " + transfer);
		IAsync<IOException> receiveBody = client.getReceiver().consume(transfer, bufferSize, config.getReceiveTimeout());
		receiveBody.onDone(() -> result.unblockSuccess(response), result);
	}
	

	/** Send the request and receive the response from the server.
	 * The response is returned as soon as the headers have been received, and the response entity
	 * is set to a BinaryEntity with an OutputToInput so the body can be read while it is received.
	 */
	public AsyncSupplier<HTTPResponse, IOException> sendAndReceiveHeadersThenBodyAsBinary(
		HTTPRequest request, int maxBodyInMemory, int maxRedirect
	) {
		AsyncSupplier<HTTPResponse, IOException> result = new AsyncSupplier<>();
		Mutable<HTTPResponse> resp = new Mutable<>(null);
		AsyncSupplier<HTTPResponse, IOException> receive = sendAndReceive(request, response -> {
			resp.set(response);
			return null;
		}, (parent, headers) -> {
			BinaryEntity entity = new BinaryEntity(parent, headers);
			IOInMemoryOrFile io = new IOInMemoryOrFile(maxBodyInMemory, Task.getCurrentPriority(), "BinaryEntity");
			entity.setContent(new OutputToInput(io, io.getSourceDescription()));
			resp.get().setEntity(entity);
			result.unblockSuccess(resp.get());
			return entity;
		}, maxRedirect);
		receive.onError(error -> {
			if (resp.get() != null && resp.get().getEntity() != null)
				((OutputToInput)((BinaryEntity)resp.get().getEntity()).getContent()).signalErrorBeforeEndOfData(error);
			result.error(error);
		});
		receive.onSuccess(() -> {
			if (!result.isDone()) {
				// empty body, entityFactory was not called
				BinaryEntity entity = new BinaryEntity(null, resp.get().getHeaders());
				entity.setContent(new EmptyReadable("empty body", Task.Priority.NORMAL));
				resp.get().setEntity(entity);
				result.unblockSuccess(resp.get());
			}
		});
		return result;
	}

	/**
	 * Send the request and start receiving the response.<br/>
	 * Equivalent to sendAndReceive(request, onHeadersReceived, entityFactory, 0).
	 * 
	 * @param request the request to send
	 * @param onHeadersReceived (may be null) called once the headers have been received.
	 *        If it returns null, the process continues. If it returns a boolean the process is stopped and the boolean value indicates
	 *        if the client must be closed or not.
	 * @param entityFactory called once the headers have been received to create an entity that will receive the response body.
	 * @return the response
	 */
	@SuppressWarnings("java:S4276") // we want a Boolean not a boolean
	public AsyncSupplier<HTTPResponse, IOException> sendAndReceive(
		HTTPRequest request,
		Function<HTTPResponse, Boolean> onHeadersReceived,
		MimeEntityFactory entityFactory
	) {
		return sendAndReceive(request, onHeadersReceived, entityFactory, 0);
	}
	
	/**
	 * Send the request and start receiving the response with automatic redirection.<br/>
	 * 
	 * @param request the request to send
	 * @param onHeadersReceived (may be null) called once the headers have been received.
	 *        If it returns null, the process continues. If it returns a boolean the process is stopped and the boolean value indicates
	 *        if the client must be closed or not.
	 * @param entityFactory called once the headers have been received to create an entity that will receive the response body.
	 * @param maxRedirect maximum number of redirection to follow
	 * @return the response
	 */
	@SuppressWarnings("java:S4276") // we want a Boolean not a boolean
	public AsyncSupplier<HTTPResponse, IOException> sendAndReceive(
		HTTPRequest request,
		Function<HTTPResponse, Boolean> onHeadersReceived,
		MimeEntityFactory entityFactory,
		int maxRedirect
	) {
		Async<IOException> send = sendRequest(request);
		AsyncSupplier<HTTPResponse, IOException> result = new AsyncSupplier<>();
		send.onDone(() -> receiveResponse(null, response -> {
			if (handleRedirection(request, response, maxRedirect, result,
				newClient -> newClient.sendAndReceive(request, onHeadersReceived, entityFactory, maxRedirect - 1)
					.forward(result)))
				return Boolean.FALSE;
			if (onHeadersReceived != null)
				return onHeadersReceived.apply(response);
			return null;
		}, entityFactory).forward(result), result);
		return result;
	}
	
	@SuppressWarnings("squid:S1141") // nested try
	private boolean handleRedirection(
		HTTPRequest request, HTTPResponse response, int maxRedirect, AsyncSupplier<?, IOException> result,
		Consumer<HTTPClient> doRedirection
	) {
		if (maxRedirect <= 0)
			return false;
		int code = response.getStatusCode();
		if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308)
			return false;
		String location = response.getHeaders().getFirstRawValue(HTTPConstants.Headers.Response.LOCATION);
		if (location == null)
			return false;
		try {
			Long size = response.getHeaders().getContentLength();
			Async<IOException> skipBody;
			if (size != null && size.longValue() > 0)
				skipBody = client.getReceiver().skipBytes(size.intValue(), config.getReceiveTimeout());
			else
				skipBody = new Async<>(true);

			URI u = new URI(location);
			if (u.getHost() == null) {
				// relative
				u = new URI(generateURL(request)).resolve(u);
			}
			request.setURI(u);
			if (isCompatible(u)) {
				skipBody.onDone(() -> doRedirection.accept(HTTPClient.this));
				return true;
			}
			HTTPClient newClient;
			try { newClient = HTTPClient.create(u, config); }
			catch (Exception e) {
				result.error(new IOException("Unable to follow redirection to " + location, e));
				return true;
			}
			skipBody.onDone(() -> doRedirection.accept(newClient));
			result.onDone(newClient::close);
		} catch (URISyntaxException e) {
			result.error(new IOException("Invalid redirect location: " + location, e));
		}
		return true;
	}
	
	/**
	 * Send the request and download the response into the given file.
	 * 
	 * @param request the request to send
	 * @param file the file to save the response body
	 * @param maxRedirect maximum number of redirection to follow
	 * @return the response
	 */
	public AsyncSupplier<HTTPResponse, IOException> download(
		HTTPRequest request, File file, int maxRedirect
	) {
		return sendAndReceive(request, null, (parent, headers) -> {
			BinaryFileEntity entity = new BinaryFileEntity(parent, headers);
			entity.setFile(file);
			return entity;
		}, maxRedirect);
	}
	
	@Override
	public void close() {
		client.close();
	}
	
}
