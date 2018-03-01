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

import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.AsyncWork.AsyncWorkListener;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
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
import net.lecousin.framework.util.Provider;
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
	@SuppressWarnings("resource")
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
			client = new SSLClient();
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
	@SuppressWarnings("resource")
	public SynchronizationPoint<IOException> sendRequest(HTTPRequest request) {
		// connection
		SynchronizationPoint<IOException> connect = null;
		if (!client.isClosed())
			connect = new SynchronizationPoint<>(true);
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
				} else if (port != 80)
					url.append(':').append(port);
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
					inet = new InetSocketAddress(inet.getHostName(), inet.getPort());
					if (client instanceof SSLClient) {
						// we have to create a HTTP tunnel with the proxy
						TCPClient tunnelClient = new TCPClient();
						SynchronizationPoint<IOException> tunnelConnect =
							tunnelClient.connect(inet, config.getConnectionTimeout(), config.getSocketOptionsArray());
						connect = new SynchronizationPoint<>();
						SynchronizationPoint<IOException> result = connect;
						// prepare the CONNECT request
						HTTPRequest connectRequest = new HTTPRequest(Method.CONNECT, hostname + ":" + port);
						connectRequest.getMIME().addHeaderRaw(HTTPRequest.HEADER_HOST, hostname + ":" + port);
						UnprotectedStringBuffer s = new UnprotectedStringBuffer(new UnprotectedString(512));
						connectRequest.generateCommandLine(s);
						s.append("\r\n");
						connectRequest.getMIME().appendHeadersTo(s);
						s.append("\r\n");
						ByteBuffer data = ByteBuffer.wrap(s.toUsAsciiBytes());
						tunnelConnect.listenInline(() -> {
							ISynchronizationPoint<IOException> send = tunnelClient.send(data);
							send.listenInline(() -> {
								AsyncWork<HTTPResponse, IOException> response =
									HTTPResponse.receive(tunnelClient, config.getReceiveTimeout());
								response.listenInline(() -> {
									HTTPResponse resp = response.getResult();
									if (resp.getStatusCode() != 200) {
										tunnelClient.close();
										result.error(new HTTPResponseError(
											resp.getStatusCode(), resp.getStatusMessage()));
										return;
									}
									// tunnel connection established
									// replace connection of SSLClient by the tunnel
									((SSLClient)client).tunnelConnected(tunnelClient, result);
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
		
		if (connect.isUnblocked() && !connect.isSuccessful()) return connect;
		SynchronizationPoint<IOException> result = new SynchronizationPoint<>();
		
		// prepare to send data while connecting

		// call interceptors
		for (HTTPRequestInterceptor interceptor : config.getInterceptors())
			interceptor.intercept(request, hostname, port);

		final Long size;
		IO.Readable body = request.getMIME().getBodyToSend();
		if (body != null) {
			result.listenInline(() -> { body.closeAsync(); });
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
		connect.listenInline(() -> {
			ISynchronizationPoint<IOException> send = client.send(data);
			if (body == null || (size != null && size.longValue() == 0)) {
				send.listenInline(result);
				return;
			}
			ISynchronizationPoint<IOException> sendBody;
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
			} else if (body instanceof IO.Readable.Buffered)
				sendBody = ChunkedTransfer.send(client, (IO.Readable.Buffered)body);
			else
				sendBody = ChunkedTransfer.send(client, body, 128 * 1024, 8);
			sendBody.listenInline(result);
		}, result);
		return result;
	}
	
	/** Receive the response headers. */
	public AsyncWork<HTTPResponse, IOException> receiveResponseHeader() {
		return HTTPResponse.receive(client, config.getReceiveTimeout());
	}
	
	/** Receive the response header and body, and write the body to the given IO.
	 * The given IO is wrapped into an OutputToInput so we can start reading from it before
	 * the body has been fully received.
	 */
	public <T extends IO.Readable.Seekable & IO.Writable.Seekable> void receiveResponse(
		String source, T io, int bufferSize,
		AsyncWork<Pair<HTTPResponse,OutputToInput>,IOException> responseHeaderListener,
		AsyncWork<HTTPResponse,IOException> outputReceived
	) {
		receiveResponseHeader().listenInline(new AsyncWorkListener<HTTPResponse, IOException>() {
			@Override
			public void ready(HTTPResponse response) {
				if (!response.isBodyExpected()) {
					if (responseHeaderListener != null)
						responseHeaderListener.unblockSuccess(new Pair<>(response, null));
					outputReceived.unblockSuccess(response);
					return;
				}
				@SuppressWarnings("resource")
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
						SynchronizationPoint<IOException> result = new SynchronizationPoint<>();
						receiveBody(transfer, result, bufferSize);
						result.listenInline(() -> {
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
				if (responseHeaderListener != null)
					responseHeaderListener.unblockError(error);
				outputReceived.error(error);
			}
			
			@Override
			public void cancelled(CancelException event) {
				if (responseHeaderListener != null)
					responseHeaderListener.unblockCancel(event);
				outputReceived.cancel(event);
			}
		});
	}
	
	/** Receive the response header and body, and write the body to the given IO. */
	public <TIO extends IO.Writable & IO.Readable> SynchronizationPoint<IOException> receiveResponse(
		AsyncWork<HTTPResponse,IOException> responseHeaderListener,
		TIO output, int bufferSize
	) {
		SynchronizationPoint<IOException> result = new SynchronizationPoint<>();
		receiveResponseHeader().listenInline(new AsyncWorkListener<HTTPResponse, IOException>() {
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
	public <TIO extends IO.Writable & IO.Readable> SynchronizationPoint<IOException> receiveResponse(
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
		Provider.FromValue<HTTPResponse, Pair<TIO, Integer>> outputProviderOnHeadersReceived,
		AsyncWork<Pair<HTTPResponse, TIO>, IOException> onReceived
	) {
		receiveResponseHeader().listenInline(
			(response) -> {
				if (!response.isBodyExpected()) {
					onReceived.unblockSuccess(new Pair<>(response, null));
					return;
				}
				Pair<TIO, Integer> output = outputProviderOnHeadersReceived.provide(response);
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
						SynchronizationPoint<IOException> result = new SynchronizationPoint<>();
						receiveBody(transfer, result, output.getValue2().intValue());
						result.listenInline(() -> {
							onReceived.unblockSuccess(new Pair<>(response, output.getValue1()));
						}, onReceived);
					} catch (IOException error) {
						onReceived.error(error);
					}
				}
			},
			onReceived
		);
	}
	
	/** Receive the body for the given response for which the headers have been already received. */
	public <TIO extends IO.Writable & IO.Readable> SynchronizationPoint<IOException> receiveBody(
		HTTPResponse response, TIO out, int bufferSize
	) {
		SynchronizationPoint<IOException> result = new SynchronizationPoint<>();
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
	
	private void receiveBody(TransferReceiver transfer, SynchronizationPoint<IOException> result, int bufferSize) {
		client.getReceiver().readAvailableBytes(bufferSize, config.getReceiveTimeout()).listenInline(
		(data) -> {
			if (data == null) {
				result.error(new IOException("Unexpected end of data"));
				return;
			}
			AsyncWork<Boolean,IOException> t = transfer.consume(data);
			t.listenInline((end) -> {
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
