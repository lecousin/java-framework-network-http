package net.lecousin.framework.network.http.client;

import java.io.Closeable;
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
import java.util.function.Function;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.AsyncSupplier.Listener;
import net.lecousin.framework.concurrent.async.CancelException;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.out2in.OutputToInput;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPRequest.Method;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.LibraryVersion;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http.exception.UnsupportedHTTPProtocolException;
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
		Async<IOException> connect = null;
		if (!client.isClosed())
			connect = new Async<>(true);
		else {
			// get proxy if any
			ProxySelector proxySelector = config.getProxySelector();
			Proxy proxy = null;
			if (proxySelector != null) {
				StringBuilder url = new StringBuilder(128);
				if (client instanceof SSLClient)
					url.append("https://");
				else
					url.append("http://");
				url.append(hostname);
				if (client instanceof SSLClient) {
					if (port != 443)
						url.append(':').append(port);
				} else if (port != 80) {
					url.append(':').append(port);
				}
				url.append(request.getPath());
				URI uri = null;
				try {
					uri = new URI(url.toString());
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
						TCPClient tunnelClient = new TCPClient();
						Async<IOException> tunnelConnect =
							tunnelClient.connect(inet, config.getConnectionTimeout(), config.getSocketOptionsArray());
						connect = new Async<>();
						Async<IOException> result = connect;
						// prepare the CONNECT request
						HTTPRequest connectRequest = new HTTPRequest(Method.CONNECT, hostname + ":" + port);
						connectRequest.getMIME().addHeaderRaw(HTTPRequest.HEADER_HOST, hostname + ":" + port);
						UnprotectedStringBuffer s = new UnprotectedStringBuffer(new UnprotectedString(512));
						connectRequest.generateCommandLine(s);
						s.append("\r\n");
						connectRequest.getMIME().appendHeadersTo(s);
						s.append("\r\n");
						ByteBuffer data = ByteBuffer.wrap(s.toUsAsciiBytes());
						tunnelConnect.onDone(() -> {
							IAsync<IOException> send = tunnelClient.send(data);
							send.onDone(() -> {
								AsyncSupplier<HTTPResponse, IOException> response =
									HTTPResponse.receive(tunnelClient, config.getReceiveTimeout());
								response.onDone(() -> {
									HTTPResponse resp = response.getResult();
									if (resp.getStatusCode() != 200) {
										tunnelClient.close();
										result.error(new HTTPResponseError(
											resp.getStatusCode(), resp.getStatusMessage()));
										return;
									}
									// tunnel connection established
									// replace connection of SSLClient by the tunnel
									((SSLClient)client).tunnelConnected(tunnelClient, result, config.getReceiveTimeout());
								}, result);
							}, result);
						}, result);
					} else {
						connect = client.connect(inet, config.getConnectionTimeout(), config.getSocketOptionsArray());
						request.setPath(url.toString());
					}
				}
			}
			// direct connection
			if (connect == null)
				connect = client.connect(
					new InetSocketAddress(hostname, port), config.getConnectionTimeout(), config.getSocketOptionsArray());
		}
		
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
					request.getMIME().setContentLength((size = Long.valueOf(((IO.KnownSize)body).getSizeSync())).longValue());
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
		UnprotectedStringBuffer s = new UnprotectedStringBuffer(new UnprotectedString(512));
		request.generateCommandLine(s);
		s.append("\r\n");
		request.getMIME().appendHeadersTo(s);
		s.append("\r\n");
		ByteBuffer data = ByteBuffer.wrap(s.toUsAsciiBytes());
		connect.onDone(() -> {
			IAsync<IOException> send = client.send(data);
			// TODO if the connection has been closed while preparing the request, try to reconnect, but only once
			if (body == null || (size != null && size.longValue() == 0)) {
				send.onDone(result);
				return;
			}
			IAsync<IOException> sendBody;
			if (body instanceof IO.KnownSize) {
				if (body instanceof IO.Readable.Buffered)
					sendBody = IdentityTransfer.send(client, (IO.Readable.Buffered)body);
				else {
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
					sendBody = IdentityTransfer.send(client, body, bufferSize, maxBuffers);
				}
			} else if (body instanceof IO.Readable.Buffered) {
				sendBody = ChunkedTransfer.send(client, (IO.Readable.Buffered)body, request.getTrailerHeadersSuppliers());
			} else {
				sendBody = ChunkedTransfer.send(client, body, 128 * 1024, 8, request.getTrailerHeadersSuppliers());
			}
			sendBody.onDone(result);
		}, result);
		return result;
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
		AsyncSupplier<Pair<HTTPResponse,OutputToInput>,IOException> responseHeaderListener,
		AsyncSupplier<HTTPResponse,IOException> outputReceived
	) {
		receiveResponseHeader().listen(new Listener<HTTPResponse, IOException>() {
			@Override
			public void ready(HTTPResponse response) {
				if (!response.isBodyExpected()) {
					if (responseHeaderListener != null) responseHeaderListener.unblockSuccess(new Pair<>(response, null));
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
							responseHeaderListener.unblockSuccess(new Pair<>(response, output));
						outputReceived.unblockSuccess(response);
					} else {
						if (responseHeaderListener != null)
							responseHeaderListener.unblockSuccess(new Pair<>(response, output));
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
		AsyncSupplier<Pair<HTTPResponse, TIO>, IOException> onReceived
	) {
		receiveResponseHeader().onDone(
			response -> {
				if (!response.isBodyExpected()) {
					onReceived.unblockSuccess(new Pair<>(response, null));
					return;
				}
				Pair<TIO, Integer> output = outputProviderOnHeadersReceived.apply(response);
				if (output == null)
					onReceived.unblockSuccess(new Pair<>(response, null));
				else {
					try {
						response.getMIME().setBodyReceived(output.getValue1());
						TransferReceiver transfer = TransferEncodingFactory.create(response.getMIME(), output.getValue1());
						if (!transfer.isExpectingData()) {
							onReceived.unblockSuccess(new Pair<>(response, output.getValue1()));
							return;
						}
						Async<IOException> result = new Async<>();
						receiveBody(transfer, result, output.getValue2().intValue());
						result.onDone(() -> onReceived.unblockSuccess(new Pair<>(response, output.getValue1())), onReceived);
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
	
	@Override
	public void close() {
		client.close();
	}
	
}
