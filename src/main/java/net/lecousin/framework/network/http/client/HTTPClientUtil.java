package net.lecousin.framework.network.http.client;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.out2in.OutputToInput;
import net.lecousin.framework.mutable.MutableInteger;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http.exception.UnsupportedHTTPProtocolException;
import net.lecousin.framework.network.mime.MimeHeader;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.util.Pair;

//skip checkstyle: MethodName
/** Utility methods for HTTP client. */
public final class HTTPClientUtil {
	
	private HTTPClientUtil() { /* no instance */ }
	
	/** Create a client and a request for the given URI. */
	@SuppressWarnings("resource")
	public static Pair<HTTPClient, HTTPRequest> createClientAndRequest(URI uri)
	throws GeneralSecurityException, UnsupportedHTTPProtocolException {
		HTTPClient client = HTTPClient.create(uri);
		HTTPRequest request = new HTTPRequest();
		request.setPath(getRequestPath(uri));
		return new Pair<>(client, request);
	}

	/** Create a client and a request for the given URL. */
	public static Pair<HTTPClient, HTTPRequest> createClientAndRequest(String url)
	throws GeneralSecurityException, UnsupportedHTTPProtocolException, URISyntaxException {
		return createClientAndRequest(new URI(url));
	}
	
	/** Return the request full path for the given URI. */
	public static String getRequestPath(URI uri) {
		String p = uri.getRawPath();
		String q = uri.getRawQuery();
		String f = uri.getRawFragment();
		StringBuilder s = new StringBuilder(p.length() + (q != null ? 1 + q.length() : 0) + (f != null ? 1 + f.length() : 0));
		s.append(p);
		if (q != null) s.append('?').append(q);
		if (f != null) s.append('#').append(f);
		return s.toString();
	}
	
	/** Send an HTTP request, with an optional body and headers. */
	@SuppressWarnings("resource")
	public static AsyncWork<HTTPClient, IOException> send(
		HTTPRequest.Method method, String url, IO.Readable body, MimeHeader... headers
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		URI uri = new URI(url);
		HTTPClient client = HTTPClient.create(uri);
		HTTPRequest request = new HTTPRequest(method, getRequestPath(uri));
		for (int i = 0; i < headers.length; ++i)
			request.getMIME().setHeader(headers[i]);
		request.getMIME().setBodyToSend(body);
		AsyncWork<HTTPClient, IOException> result = new AsyncWork<>();
		client.sendRequest(request).listenInline(
			() -> { result.unblockSuccess(client); },
			result
		);
		return result;
	}
	
	/** Send an HTTP request, with a body and headers. */
	@SuppressWarnings("resource")
	public static AsyncWork<HTTPClient, IOException> send(
		HTTPRequest.Method method, String url, MimeMessage message
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		URI uri = new URI(url);
		HTTPClient client = HTTPClient.create(uri);
		HTTPRequest request = new HTTPRequest(method, getRequestPath(uri));
		request.getMIME().getHeaders().addAll(message.getHeaders());
		request.getMIME().setBodyToSend(message.getBodyToSend());
		AsyncWork<HTTPClient, IOException> result = new AsyncWork<>();
		client.sendRequest(request).listenInline(
			() -> { result.unblockSuccess(client); },
			result
		);
		return result;
	}
	
	/** Send an HTTP request, with an optional body and headers, then receive the response headers,
	 * but not yet the body. */
	public static AsyncWork<Pair<HTTPClient, HTTPResponse>, IOException> sendAndReceiveHeaders(
		HTTPRequest.Method method, String url, IO.Readable body, MimeHeader... headers
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncWork<HTTPClient, IOException> send = send(method, url, body, headers);
		AsyncWork<Pair<HTTPClient, HTTPResponse>, IOException> result = new AsyncWork<>();
		send.listenInline((client) -> {
			client.receiveResponseHeader().listenInline(
				(response) -> { result.unblockSuccess(new Pair<>(client, response)); },
				result
			);
		}, result);
		return result;
	}

	/** Send an HTTP request, with a body and headers, then receive the response headers,
	 * but not yet the body. */
	public static AsyncWork<Pair<HTTPClient, HTTPResponse>, IOException> sendAndReceiveHeaders(
		HTTPRequest.Method method, String url, MimeMessage message
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncWork<HTTPClient, IOException> send = send(method, url, message);
		AsyncWork<Pair<HTTPClient, HTTPResponse>, IOException> result = new AsyncWork<>();
		send.listenInline((client) -> {
			client.receiveResponseHeader().listenInline(
				(response) -> { result.unblockSuccess(new Pair<>(client, response)); },
				result
			);
		}, result);
		return result;
	}
	
	/** Send an HTTP request, with an optional body and headers, then receive the response headers,
	 * and start to receive the body (but the body may not yet fully received). */
	@SuppressWarnings("resource")
	public static AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> sendAndReceive(
		HTTPRequest.Method method, String url, IO.Readable body, MimeHeader... headers
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncWork<HTTPClient, IOException> send = send(method, url, body, headers);
		AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> result = new AsyncWork<>();
		send.listenInline((client) -> {
			client.receiveResponseHeader().listenInline(
				(response) -> {
					if ((response.getStatusCode() / 100) != 2) {
						result.unblockError(new HTTPResponseError(
							response.getStatusCode(), response.getStatusMessage()
						));
						client.close();
						return;
					}
					IOInMemoryOrFile io = new IOInMemoryOrFile(1024 * 1024, Task.PRIORITY_NORMAL, url.toString());
					OutputToInput output = new OutputToInput(io, url.toString());
					client.receiveBody(response, output, 64 * 1024).listenInline(
						() -> {
							output.endOfData();
							client.close();
						}
					);
					result.unblockSuccess(new Pair<>(response, output));
				},
				result
			);
		}, result);
		return result;
	}

	/** Send an HTTP request, with a body and headers, then receive the response headers,
	 * and start to receive the body (but the body may not yet fully received). */
	@SuppressWarnings("resource")
	public static AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> sendAndReceive(
		HTTPRequest.Method method, String url, MimeMessage message
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncWork<HTTPClient, IOException> send = send(method, url, message);
		AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> result = new AsyncWork<>();
		send.listenInline((client) -> {
			client.receiveResponseHeader().listenInline(
				(response) -> {
					if ((response.getStatusCode() / 100) != 2) {
						result.unblockError(new HTTPResponseError(
							response.getStatusCode(), response.getStatusMessage()
						));
						client.close();
						return;
					}
					IOInMemoryOrFile io = new IOInMemoryOrFile(1024 * 1024, Task.PRIORITY_NORMAL, url.toString());
					OutputToInput output = new OutputToInput(io, url.toString());
					client.receiveBody(response, output, 64 * 1024).listenInline(
						() -> {
							output.endOfData();
							client.close();
						}
					);
					result.unblockSuccess(new Pair<>(response, output));
				},
				result
			);
		}, result);
		return result;
	}
	
	/** Send an HTTP request, with an optional body and headers, then receive the response headers,
	 * and receive the full body before to unblock the returned AsyncWork. */
	@SuppressWarnings("resource")
	public static AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> sendAndReceiveFully(
		HTTPRequest.Method method, String url, IO.Readable body, MimeHeader... headers
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncWork<HTTPClient, IOException> send = send(method, url, body, headers);
		AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> result = new AsyncWork<>();
		send.listenInline((client) -> {
			client.receiveResponseHeader().listenInline(
				(response) -> {
					if ((response.getStatusCode() / 100) != 2) {
						result.unblockError(new HTTPResponseError(
							response.getStatusCode(), response.getStatusMessage()
						));
						client.close();
						return;
					}
					IOInMemoryOrFile io = new IOInMemoryOrFile(1024 * 1024, Task.PRIORITY_NORMAL, url.toString());
					OutputToInput output = new OutputToInput(io, url.toString());
					client.receiveBody(response, output, 64 * 1024).listenInline(
						() -> {
							output.endOfData();
							result.unblockSuccess(new Pair<>(response, output));
							client.close();
						},
						(error) -> {
							result.error(error);
							client.close();
						},
						(cancel) -> {
							result.cancel(cancel);
							client.close();
						}
					);
				},
				result
			);
		}, result);
		return result;
	}

	/** Send an HTTP request, with a body and headers, then receive the response headers,
	 * and receive the full body before to unblock the returned AsyncWork. */
	@SuppressWarnings("resource")
	public static AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> sendAndReceiveFully(
		HTTPRequest.Method method, String url, MimeMessage message
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncWork<HTTPClient, IOException> send = send(method, url, message);
		AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> result = new AsyncWork<>();
		send.listenInline((client) -> {
			client.receiveResponseHeader().listenInline(
				(response) -> {
					if ((response.getStatusCode() / 100) != 2) {
						result.unblockError(new HTTPResponseError(
							response.getStatusCode(), response.getStatusMessage()
						));
						client.close();
						return;
					}
					IOInMemoryOrFile io = new IOInMemoryOrFile(1024 * 1024, Task.PRIORITY_NORMAL, url.toString());
					OutputToInput output = new OutputToInput(io, url.toString());
					client.receiveBody(response, output, 64 * 1024).listenInline(
						() -> {
							output.endOfData();
							result.unblockSuccess(new Pair<>(response, output));
							client.close();
						},
						(error) -> {
							result.error(error);
							client.close();
						},
						(cancel) -> {
							result.cancel(cancel);
							client.close();
						}
					);
				},
				result
			);
		}, result);
		return result;
	}
	
	/**
	 * Send a GET request to the given URL, and receive the headers in the HTTPResponse, but not the body.
	 * The body can then be read using the returned HTTPClient and HTTPResponse.<br/>
	 * If maxRedirects is positive, the redirections are automatically handled.<br/>
	 * The given headers are added to the request, including the subsequent requests in case of redirection.
	 */
	public static AsyncWork<Pair<HTTPClient, HTTPResponse>, IOException> sendGET(
		String url, int maxRedirects, MimeHeader... headers
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncWork<HTTPClient, IOException> send = send(HTTPRequest.Method.GET, url, (IO.Readable)null, headers);
		AsyncWork<Pair<HTTPClient, HTTPResponse>, IOException> result = new AsyncWork<>();
		MutableInteger redirectCount = new MutableInteger(0);
		send.listenInline((client) -> {
			client.receiveResponseHeader().listenInline(
				(response) -> {
					boolean isRedirect;
					if (maxRedirects - redirectCount.get() <= 0) isRedirect = false;
					else if (!response.getMIME().hasHeader("Location")) isRedirect = false;
					else {
						int code = response.getStatusCode();
						if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308)
							isRedirect = true;
						else
							isRedirect = false;
					}
					if (!isRedirect) {
						result.unblockSuccess(new Pair<>(client, response));
						return;
					}
					client.close();
					String location = response.getMIME().getFirstHeaderRawValue("Location");
					try {
						URI u = new URI(location);
						if (u.getHost() == null) {
							// relative
							URI u2 = new URI(url).resolve(u);
							location = u2.toString();
						}
						// absolute
						try {
							sendGET(location, maxRedirects - 1, headers).listenInline(result);
						} catch (Exception e) {
							result.error(IO.error(e));
						}
					} catch (URISyntaxException e) {
						result.error(new IOException("Invalid redirect location: " + location, e));
					}
				}, result
			);
		}, result);
		return result;
	}
	
	
	/**
	 * Send a GET request, and receive the response into the given file.
	 */
	public static AsyncWork<Pair<HTTPResponse, FileIO.ReadWrite>, IOException> GET(
		String url, File file, int maxRedirects, MimeHeader... headers
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncWork<Pair<HTTPResponse, FileIO.ReadWrite>, IOException> result = new AsyncWork<>();
		sendGET(url, maxRedirects, headers).listenInline((p) -> {
			@SuppressWarnings("resource")
			HTTPClient client = p.getValue1();
			HTTPResponse response = p.getValue2();
			if ((response.getStatusCode() / 100) != 2) {
				result.unblockError(new HTTPResponseError(response.getStatusCode(), response.getStatusMessage()));
				client.close();
				return;
			}
			@SuppressWarnings("resource")
			FileIO.ReadWrite io = new FileIO.ReadWrite(file, Task.PRIORITY_NORMAL);
			client.receiveBody(response, io, 64 * 1024).listenInline(
				() -> {
					AsyncWork<Long, IOException> seek = io.seekAsync(SeekType.FROM_BEGINNING, 0);
					seek.listenInline(new Runnable() {
						@Override
						public void run() {
							if (seek.hasError()) {
								result.unblockError(seek.getError());
								try { io.close(); } catch (Throwable t) { /* ignore */ }
								file.delete();
							} else
								result.unblockSuccess(new Pair<>(response, io));
						}
					});
					client.close();
				},
				(error) -> {
					result.error(error);
					client.close();
					try { io.close(); } catch (Throwable t) { /* ignore */ }
					file.delete();
				},
				(cancel) -> {
					result.cancel(cancel);
					client.close();
					try { io.close(); } catch (Throwable t) { /* ignore */ }
					file.delete();
				}
			);
		}, result);
		return result;
	}

	/**
	 * Send a GET request, and receive the response.
	 * The returned IO is not yet filled but will be.
	 */
	public static AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> GET(
		String url, int maxRedirects, MimeHeader... headers
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> result = new AsyncWork<>();
		sendGET(url, maxRedirects, headers).listenInline((p) -> {
			@SuppressWarnings("resource")
			HTTPClient client = p.getValue1();
			HTTPResponse response = p.getValue2();
			if ((response.getStatusCode() / 100) != 2) {
				result.unblockError(new HTTPResponseError(response.getStatusCode(), response.getStatusMessage()));
				client.close();
				return;
			}
			@SuppressWarnings("resource")
			IOInMemoryOrFile io = new IOInMemoryOrFile(1024 * 1024, Task.PRIORITY_NORMAL, url.toString());
			@SuppressWarnings("resource")
			OutputToInput output = new OutputToInput(io, url.toString());
			client.receiveBody(response, output, 64 * 1024).listenInline(
				() -> {
					output.endOfData();
					client.close();
				},
				(error) -> {
					output.signalErrorBeforeEndOfData(error);
					client.close();
				},
				(cancel) -> {
					output.signalErrorBeforeEndOfData(IO.error(cancel));
					client.close();
				}
			);
			result.unblockSuccess(new Pair<>(response, output));
		}, result);
		return result;
	}

	/**
	 * Send a GET request, and receive the response.
	 * The returned IO is completely filled.
	 */
	public static AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> GETfully(
		String url, int maxRedirects, MimeHeader... headers
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncWork<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> result = new AsyncWork<>();
		sendGET(url, maxRedirects, headers).listenInline((p) -> {
			@SuppressWarnings("resource")
			HTTPClient client = p.getValue1();
			HTTPResponse response = p.getValue2();
			if ((response.getStatusCode() / 100) != 2) {
				result.unblockError(new HTTPResponseError(response.getStatusCode(), response.getStatusMessage()));
				client.close();
				return;
			}
			@SuppressWarnings("resource")
			IOInMemoryOrFile io = new IOInMemoryOrFile(1024 * 1024, Task.PRIORITY_NORMAL, url.toString());
			@SuppressWarnings("resource")
			OutputToInput output = new OutputToInput(io, url.toString());
			client.receiveBody(response, output, 64 * 1024).listenInline(
				() -> {
					output.endOfData();
					result.unblockSuccess(new Pair<>(response, output));
					client.close();
				},
				(error) -> {
					result.error(error);
					client.close();
					try { output.close(); } catch (Throwable t) { /* ignore */ }
				},
				(cancel) -> {
					result.cancel(cancel);
					client.close();
					try { output.close(); } catch (Throwable t) { /* ignore */ }
				}
			);
		}, result);
		return result;
	}

}
