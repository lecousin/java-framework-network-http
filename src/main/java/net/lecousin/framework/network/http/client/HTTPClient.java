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
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.AsyncSupplier.Listener;
import net.lecousin.framework.concurrent.async.CancelException;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.tasks.drives.RemoveFileTask;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.out2in.OutputToInput;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPRequest.Method;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.LibraryVersion;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http.exception.UnsupportedHTTPProtocolException;
import net.lecousin.framework.network.mime.MimeHeader;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.transfer.ChunkedTransfer;
import net.lecousin.framework.network.mime.transfer.IdentityTransfer;
import net.lecousin.framework.network.mime.transfer.TransferEncodingFactory;
import net.lecousin.framework.network.mime.transfer.TransferReceiver;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.UnprotectedString;
import net.lecousin.framework.util.UnprotectedStringBuffer;

/** HTTP client. */
public class HTTPClient implements Closeable {
	
	public static final String USER_AGENT = "net.lecousin.framework.network.http.client/" + LibraryVersion.VERSION;
	
	public static final int DEFAULT_HTTP_PORT = 80;
	public static final int DEFAULT_HTTPS_PORT = 443;

	/** Constructor. */
	public HTTPClient(TCPClient client, String hostname, int port, HTTPClientConfiguration config) {
		this.client = client;
		this.hostname = hostname;
		this.port = port;
		this.config = config;
	}

	/** Constructor. */
	public HTTPClient(TCPClient client, String hostname, HTTPClientConfiguration config) {
		this(client, hostname, client instanceof SSLClient ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT, config);
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
				port = DEFAULT_HTTP_PORT;
			else
				port = DEFAULT_HTTPS_PORT;
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
			if (port != DEFAULT_HTTPS_PORT)
				url.append(':').append(port);
		} else if (port != DEFAULT_HTTP_PORT) {
			url.append(':').append(port);
		}
		url.append(request.getPath());
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
				p = DEFAULT_HTTPS_PORT;
			else
				p = DEFAULT_HTTP_PORT;
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
	 *  is known, the buffer size is set to 1MB for a body larger than 4MB, to 512KB for a body larger
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

		final Long size;
		IO.Readable body = request.getMIME().getBodyToSend();
		if (body != null) {
			result.onDone(body::closeAsync);
			if (body instanceof IO.KnownSize) {
				try {
					size = Long.valueOf(((IO.KnownSize)body).getSizeSync());
					request.getMIME().setContentLength(size.longValue());
				} catch (IOException e) {
					result.error(e);
					return result;
				}
				//request.getMIME().setHeader(MIME.TRANSFER_ENCODING, "8bit");
			} else {
				request.getMIME().setHeaderRaw(MimeMessage.TRANSFER_ENCODING, "chunked");
				size = null;
			}
		} else {
			size = Long.valueOf(0);
			request.getMIME().setContentLength(0);
		}
		ByteBuffer data = generateCommandAndHeaders(request);
		
		connect.onDone(() -> {
			IAsync<IOException> send = client.send(data);
			if (body == null || (size != null && size.longValue() == 0)) {
				send.onDone(result);
				return;
			}
			sendBody(body, size, request).onDone(result);
		}, result);
		return result;
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
					@SuppressWarnings("squid:S2095") // it is closed
					TCPClient tunnelClient = new TCPClient();
					Async<IOException> tunnelConnect =
						tunnelClient.connect(inet, config.getConnectionTimeout(), config.getSocketOptionsArray());
					Async<IOException> connect = new Async<>();
					// prepare the CONNECT request
					HTTPRequest connectRequest = new HTTPRequest(Method.CONNECT, hostname + ":" + port);
					connectRequest.getMIME().addHeaderRaw(HTTPRequest.HEADER_HOST, hostname + ":" + port);
					ByteBuffer data = generateCommandAndHeaders(connectRequest);
					tunnelConnect.onDone(
						() -> tunnelClient.send(data).onDone(
						() -> HTTPResponse.receive(tunnelClient, config.getReceiveTimeout()).onDone(
						resp -> {
							if (resp.getStatusCode() != 200) {
								tunnelClient.close();
								connect.error(new HTTPResponseError(resp.getStatusCode(), resp.getStatusMessage()));
								return;
							}
							// tunnel connection established
							// replace connection of SSLClient by the tunnel
							((SSLClient)client).tunnelConnected(tunnelClient, connect, config.getReceiveTimeout());
						}, connect), connect), connect);
					connect.onErrorOrCancel(tunnelClient::close);
					return connect;
				}
				Async<IOException> connect = client.connect(inet, config.getConnectionTimeout(), config.getSocketOptionsArray());
				request.setPath(url);
				return connect;
			}
		}
		// direct connection
		return client.connect(
			new InetSocketAddress(hostname, port), config.getConnectionTimeout(), config.getSocketOptionsArray());
	}
	
	private static ByteBuffer generateCommandAndHeaders(HTTPRequest request) {
		UnprotectedStringBuffer s = new UnprotectedStringBuffer(new UnprotectedString(512));
		request.generateCommandLine(s);
		s.append("\r\n");
		request.getMIME().appendHeadersTo(s);
		s.append("\r\n");
		return ByteBuffer.wrap(s.toUsAsciiBytes());
	}
	
	private IAsync<IOException> sendBody(IO.Readable body, Long size, HTTPRequest request) {
		if (body instanceof IO.KnownSize) {
			if (body instanceof IO.Readable.Buffered)
				return IdentityTransfer.send(client, (IO.Readable.Buffered)body);
			int bufferSize;
			int maxBuffers;
			long si = size.longValue();
			if (si >= 4 * 1024 * 1024) {
				bufferSize = 1024 * 1024;
				maxBuffers = 4;
			} else if (si >= 1024 * 1024) {
				bufferSize = 512 * 1024;
				maxBuffers = 6;
			} else if (si >= 128 * 1024) {
				bufferSize = 64 * 1024;
				maxBuffers = 10;
			} else if (si >= 32 * 1024) {
				bufferSize = 16 * 1024;
				maxBuffers = 4;
			} else {
				bufferSize = (int)si;
				maxBuffers = 1;
			}
			return IdentityTransfer.send(client, body, bufferSize, maxBuffers);
		}
		
		if (body instanceof IO.Readable.Buffered)
			return ChunkedTransfer.send(client, (IO.Readable.Buffered)body, request.getTrailerHeadersSuppliers());

		return ChunkedTransfer.send(client, body, 128 * 1024, 8, request.getTrailerHeadersSuppliers());
	}
	
	/** Receive the response headers. */
	public AsyncSupplier<HTTPResponse, IOException> receiveResponseHeader() {
		return HTTPResponse.receive(client, config.getReceiveTimeout());
	}
	
	/** Receive the response header and body, and write the body to the given IO.
	 * The given IO is wrapped into an OutputToInput so we can start reading from it before
	 * the body has been fully received.
	 */
	public <T extends IO.Readable.Seekable & IO.Writable.Seekable> void receiveResponse(
		String source, T io, int bufferSize,
		AsyncSupplier<HTTPResponse, IOException> responseHeaderListener,
		AsyncSupplier<HTTPResponse, IOException> outputReceived
	) {
		receiveResponseHeader().listen(new Listener<HTTPResponse, IOException>() {
			@Override
			public void ready(HTTPResponse response) {
				if (!response.isBodyExpected()) {
					if (responseHeaderListener != null) responseHeaderListener.unblockSuccess(response);
					outputReceived.unblockSuccess(response);
					return;
				}
				OutputToInput output = new OutputToInput(io, source);
				try {
					response.getMIME().setBodyReceived(output);
					TransferReceiver transfer = TransferEncodingFactory.create(response.getMIME(), output);
					if (!transfer.isExpectingData()) {
						output.endOfData();
						if (responseHeaderListener != null)
							responseHeaderListener.unblockSuccess(response);
						outputReceived.unblockSuccess(response);
					} else {
						if (responseHeaderListener != null)
							responseHeaderListener.unblockSuccess(response);
						Async<IOException> result = new Async<>();
						receiveBody(transfer, result, bufferSize);
						result.onDone(() -> {
							output.endOfData();
							outputReceived.unblockSuccess(response);
						}, outputReceived);
					}
				} catch (IOException error) {
					outputReceived.unblockError(error);
				}
			}
			
			@Override
			public void error(IOException error) {
				if (responseHeaderListener != null) responseHeaderListener.unblockError(error);
				outputReceived.error(error);
			}
			
			@Override
			public void cancelled(CancelException event) {
				if (responseHeaderListener != null) responseHeaderListener.unblockCancel(event);
				outputReceived.cancel(event);
			}
		});
	}
	
	/** Receive the response header and body, and write the body to the given IO. */
	public <TIO extends IO.Writable & IO.Readable> Async<IOException> receiveResponse(
		AsyncSupplier<HTTPResponse,IOException> responseHeaderListener,
		TIO output, int bufferSize
	) {
		Async<IOException> result = new Async<>();
		receiveResponseHeader().listen(new Listener<HTTPResponse, IOException>() {
			@Override
			public void ready(HTTPResponse response) {
				if (responseHeaderListener != null)
					responseHeaderListener.unblockSuccess(response);
				if (!response.isBodyExpected()) {
					result.unblock();
					return;
				}
				try {
					response.getMIME().setBodyReceived(output);
					TransferReceiver transfer = TransferEncodingFactory.create(response.getMIME(), output);
					if (!transfer.isExpectingData()) {
						result.unblock();
						return;
					}
					receiveBody(transfer, result, bufferSize);
				} catch (IOException error) {
					result.error(error);
				}
			}
			
			@Override
			public void error(IOException error) {
				if (responseHeaderListener != null)
					responseHeaderListener.unblockError(error);
				result.error(error);
			}
			
			@Override
			public void cancelled(CancelException event) {
				if (responseHeaderListener != null)
					responseHeaderListener.unblockCancel(event);
				result.cancel(event);
			}
		});
		return result;
	}
	
	/** Receive the response header and body, and write the body to the given IO. */
	public <TIO extends IO.Writable & IO.Readable> Async<IOException> receiveResponse(
		TIO output, int bufferSize
	) {
		return receiveResponse(null, output, bufferSize);
	}
	
	/** Receive the response header, then the body to the IO provided by the provider.
	 * The provider gives a Writable and a buffer size based on the HTTP response headers received.
	 * The provider may not be called, and the IO set to null in the response, in case it is detected
	 * that there will be no body.
	 * If the provider provides a null pair, the body is not received.
	 */
	public <TIO extends IO.Writable & IO.Readable> void receiveResponse(
		Function<HTTPResponse, Pair<TIO, Integer>> outputProviderOnHeadersReceived,
		AsyncSupplier<HTTPResponse, IOException> onReceived
	) {
		receiveResponseHeader().onDone(
			response -> {
				if (!response.isBodyExpected()) {
					onReceived.unblockSuccess(response);
					return;
				}
				Pair<TIO, Integer> output = outputProviderOnHeadersReceived.apply(response);
				if (output == null)
					onReceived.unblockSuccess(response);
				else {
					try {
						response.getMIME().setBodyReceived(output.getValue1());
						TransferReceiver transfer = TransferEncodingFactory.create(response.getMIME(), output.getValue1());
						if (!transfer.isExpectingData()) {
							onReceived.unblockSuccess(response);
							return;
						}
						Async<IOException> result = new Async<>();
						receiveBody(transfer, result, output.getValue2().intValue());
						result.onDone(() -> onReceived.unblockSuccess(response), onReceived);
					} catch (IOException error) {
						onReceived.error(error);
					}
				}
			},
			onReceived
		);
	}
	
	/** Receive the body for the given response for which the headers have been already received. */
	public <TIO extends IO.Writable & IO.Readable> Async<IOException> receiveBody(
		HTTPResponse response, TIO out, int bufferSize
	) {
		Async<IOException> result = new Async<>();
		if (!response.isBodyExpected()) {
			result.unblock();
			return result;
		}
		try {
			response.getMIME().setBodyReceived(out);
			TransferReceiver transfer = TransferEncodingFactory.create(response.getMIME(), out);
			if (!transfer.isExpectingData()) {
				result.unblock();
				return result;
			}
			receiveBody(transfer, result, bufferSize);
		} catch (IOException error) {
			result.error(error);
		}
		return result;
	}
	
	private void receiveBody(TransferReceiver transfer, Async<IOException> result, int bufferSize) {
		client.getReceiver().readAvailableBytes(bufferSize, config.getReceiveTimeout()).onDone(
		data -> {
			if (data == null) {
				result.error(new IOException("Unexpected end of data"));
				return;
			}
			AsyncSupplier<Boolean,IOException> t = transfer.consume(data);
			t.onDone(end -> {
				if (end.booleanValue()) {
					if (data.hasRemaining()) {
						// TODO it must not happen, but we have to request to the transfer,
						// how many bytes are expected!
					}
					result.unblock();
					return;
				}
				receiveBody(transfer, result, bufferSize);
			}, result);
		}, result);
	}
	
	/** Send the given request and receive response headers.
	 * If a body is expected the receiveBody method should be used.
	 * 
	 * @param request the request to send
	 * @return the HTTPResponse with status code and headers, but without body.
	 */
	public AsyncSupplier<HTTPResponse, IOException> sendAndReceiveHeaders(HTTPRequest request) {
		AsyncSupplier<HTTPResponse, IOException> result = new AsyncSupplier<>();
		sendRequest(request).onDone(() -> receiveResponseHeader().forward(result), result);
		return result;
	}

	/**
	 * Send the request and start receiving the response.<br/>
	 * If the response status is not 2xx, the body is not received and an HTTPResponseError is returned.<br/>
	 * The body can be read from the response.getMIME().getBodyReceivedAsInput().<br/>
	 * If the response contains a Content-Length header and the length is not greater than 128K, a ByteArrayIO is used, else
	 * a IOInMemoryOrFile is used with 64K in memory.<br/>
	 * This method is equivalent to sendAndReceive(request, true, false, 0).
	 * 
	 * @param request the request to send
	 * @return the response
	 */
	public AsyncSupplier<HTTPResponse, IOException> sendAndReceive(HTTPRequest request) {
		return sendAndReceive(request, true, false, 0);
	}

	/**
	 * Send the request and start receiving the response.<br/>
	 * If the response status is not 2xx, the body is not received and an HTTPResponseError is returned.<br/>
	 * The body can be read from the response.getMIME().getBodyReceivedAsInput().<br/>
	 * If the response contains a Content-Length header and the length is not greater than 128K, a ByteArrayIO is used, else
	 * a IOInMemoryOrFile is used with 64K in memory.<br/>
	 * This method is equivalent to sendAndReceive(request, true, false, maxRedirect).
	 * 
	 * @param request the request to send
	 * @param maxRedirect maximum number of redirection to follow
	 * @return the response
	 */
	public AsyncSupplier<HTTPResponse, IOException> sendAndReceive(HTTPRequest request, int maxRedirect) {
		return sendAndReceive(request, true, false, maxRedirect);
	}

	/**
	 * Send the request and start receiving the response.<br/>
	 * If stopOnError is true and the response status is not 2xx, the body is not received and an HTTPResponseError is returned.<br/>
	 * The body can be read from the response.getMIME().getBodyReceivedAsInput().<br/>
	 * If the response contains a Content-Length header and the length is not greater than 128K, a ByteArrayIO is used, else
	 * a IOInMemoryOrFile is used with 64K in memory.<br/>
	 * This method is equivalent to sendAndReceive(request, stopOnError, false, 0).
	 * 
	 * @param request the request to send
	 * @param stopOnError true to do not receive the body and return an HTTPResponseError if the response status code is not 2xx.
	 * @return the response
	 */
	public AsyncSupplier<HTTPResponse, IOException> sendAndReceive(HTTPRequest request, boolean stopOnError) {
		return sendAndReceive(request, stopOnError, false, 0);
	}
	
	/**
	 * Send the request and start receiving the response.<br/>
	 * If stopOnError is true and the response status is not 2xx, the body is not received and an HTTPResponseError is returned.<br/>
	 * The body can be read from the response.getMIME().getBodyReceivedAsInput().<br/>
	 * If the response contains a Content-Length header and the length is not greater than 128K, a ByteArrayIO is used, else
	 * a IOInMemoryOrFile is used with 64K in memory.
	 * 
	 * @param request the request to send
	 * @param stopOnError true to do not receive the body and return an HTTPResponseError if the response status code is not 2xx.
	 * @param waitFullBody if true the response is unblocked only once the body has been fully received.
	 * @param maxRedirect maximum number of redirection to follow
	 * @return the response
	 */
	public AsyncSupplier<HTTPResponse, IOException> sendAndReceive(
		HTTPRequest request, boolean stopOnError, boolean waitFullBody, int maxRedirect
	) {
		AsyncSupplier<HTTPResponse, IOException> result = new AsyncSupplier<>();
		Async<IOException> send = sendRequest(request);
		String description = generateURL(request);
		send.onDone(() -> receiveResponseHeader().onDone(response -> {
			if (handleRedirection(request, response, maxRedirect, result,
				newClient -> newClient.sendAndReceive(request, stopOnError, waitFullBody, maxRedirect - 1).forward(result)))
				return;
			if (stopOnError && (response.getStatusCode() / 100) != 2) {
				result.unblockError(new HTTPResponseError(response));
				close();
				return;
			}
			if (!response.isBodyExpected()) {
				result.unblockSuccess(response);
				return;
			}
			Long size = response.getMIME().getContentLength();
			OutputToInput output = new OutputToInput(initIO(size, description), description);
			int bufferSize = size != null && size.longValue() < 64 * 1024 ? size.intValue() : 64 * 1024;
			receiveBody(response, output, bufferSize).onDone(() -> {
				output.endOfData();
				if (waitFullBody)
					result.unblockSuccess(response);
			}, error -> {
				output.signalErrorBeforeEndOfData(error);
				if (waitFullBody)
					result.error(error);
			}, cancel -> {
				output.signalErrorBeforeEndOfData(IO.errorCancelled(cancel));
				if (waitFullBody)
					result.cancel(new CancelException("Download has been cancelled", cancel));
			});
			if (!waitFullBody)
				result.unblockSuccess(response);
		}, result), result);
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
		String location = response.getMIME().getFirstHeaderRawValue("Location");
		if (location == null)
			return false;
		try {
			Long size = response.getMIME().getContentLength();
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
	
	@SuppressWarnings("unchecked")
	private static <T extends IO.Readable.Seekable & IO.Writable.Seekable> T initIO(Long size, String description) {
		if (size != null && size.longValue() <= 128 * 1024)
			return (T)new ByteArrayIO(size.intValue(), description);
		return (T)new IOInMemoryOrFile(64 * 1024, Task.PRIORITY_NORMAL, description);
	}
	
	/**
	 * Send the request and download the response into the given file.
	 * 
	 * @param request the request to send
	 * @param file the file to save the response body
	 * @param maxRedirect maximum number of redirection to follow
	 * @return the response and the file at position 0. The file may be null if no body has been received.
	 */
	public AsyncSupplier<Pair<HTTPResponse, FileIO.ReadWrite>, IOException> download(
		HTTPRequest request, File file, int maxRedirect
	) {
		AsyncSupplier<Pair<HTTPResponse, FileIO.ReadWrite>, IOException> result = new AsyncSupplier<>();
		Async<IOException> send = sendRequest(request);
		send.onDone(() -> receiveResponseHeader().onDone(response -> {
			if (handleRedirection(request, response, maxRedirect, result,
				newClient -> newClient.download(request, file, maxRedirect - 1).forward(result)))
				return;
			if ((response.getStatusCode() / 100) != 2) {
				result.unblockError(new HTTPResponseError(response));
				close();
				return;
			}
			if (!response.isBodyExpected()) {
				result.unblockSuccess(new Pair<>(response, null));
				return;
			}
			FileIO.ReadWrite io = new FileIO.ReadWrite(file, Task.PRIORITY_NORMAL);
			receiveBody(response, io, 64 * 1024).onDone(() -> {
				AsyncSupplier<Long, IOException> seek = io.seekAsync(SeekType.FROM_BEGINNING, 0);
				seek.onDone(() -> result.unblockSuccess(new Pair<>(response, io)), result);
			});
			result.onErrorOrCancel(() -> io.closeAsync().thenStart(new RemoveFileTask(file, Task.PRIORITY_NORMAL), true));
		}, result), result);
		return result;
	}
	
	@Override
	public void close() {
		client.close();
	}
	
	/** Utility method to create a client, send a GET request, receive the response, and close the client once the response is done. */
	public static AsyncSupplier<HTTPResponse, IOException> get(URI uri, int maxRedirect, MimeHeader... headers)
	throws UnsupportedHTTPProtocolException, GeneralSecurityException {
		HTTPClient client = HTTPClient.create(uri);
		AsyncSupplier<HTTPResponse, IOException> send =
			client.sendAndReceive(new HTTPRequest(Method.GET).setURI(uri).setHeaders(headers), true, true, maxRedirect);
		send.onDone(client::close);
		return send;
	}
	
}
