package net.lecousin.framework.network.http.client;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
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
	public static AsyncSupplier<HTTPClient, IOException> send(
		HTTPRequest.Method method, String url, IO.Readable body, MimeHeader... headers
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		URI uri = new URI(url);
		HTTPClient client = HTTPClient.create(uri);
		HTTPRequest request = new HTTPRequest(method, getRequestPath(uri));
		for (int i = 0; i < headers.length; ++i)
			request.getMIME().setHeader(headers[i]);
		request.getMIME().setBodyToSend(body);
		AsyncSupplier<HTTPClient, IOException> result = new AsyncSupplier<>();
		client.sendRequest(request).onDone(() -> result.unblockSuccess(client), result);
		return result;
	}
	
	/** Send an HTTP request, with a body and headers. */
	public static AsyncSupplier<HTTPClient, IOException> send(
		HTTPRequest.Method method, String url, MimeMessage message
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		URI uri = new URI(url);
		HTTPClient client = HTTPClient.create(uri);
		HTTPRequest request = new HTTPRequest(method, getRequestPath(uri));
		request.getMIME().getHeaders().addAll(message.getHeaders());
		request.getMIME().setBodyToSend(message.getBodyToSend());
		AsyncSupplier<HTTPClient, IOException> result = new AsyncSupplier<>();
		client.sendRequest(request).onDone(() -> result.unblockSuccess(client), result);
		return result;
	}
	
	/** Send an HTTP request, with an optional body and headers, then receive the response headers,
	 * but not yet the body. */
	public static AsyncSupplier<Pair<HTTPClient, HTTPResponse>, IOException> sendAndReceiveHeaders(
		HTTPRequest.Method method, String url, IO.Readable body, MimeHeader... headers
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncSupplier<HTTPClient, IOException> send = send(method, url, body, headers);
		AsyncSupplier<Pair<HTTPClient, HTTPResponse>, IOException> result = new AsyncSupplier<>();
		send.onDone(client -> client.receiveResponseHeader().onDone(
			response -> result.unblockSuccess(new Pair<>(client, response)), result), result);
		return result;
	}

	/** Send an HTTP request, with a body and headers, then receive the response headers,
	 * but not yet the body. */
	public static AsyncSupplier<Pair<HTTPClient, HTTPResponse>, IOException> sendAndReceiveHeaders(
		HTTPRequest.Method method, String url, MimeMessage message
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncSupplier<HTTPClient, IOException> send = send(method, url, message);
		AsyncSupplier<Pair<HTTPClient, HTTPResponse>, IOException> result = new AsyncSupplier<>();
		send.onDone(client -> client.receiveResponseHeader().onDone(
			response -> result.unblockSuccess(new Pair<>(client, response)), result), result);
		return result;
	}
	
	/** Send an HTTP request, with an optional body and headers, then receive the response headers,
	 * and start to receive the body (but the body may not yet fully received). */
	public static AsyncSupplier<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> sendAndReceive(
		HTTPRequest.Method method, String url, IO.Readable body, MimeHeader... headers
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncSupplier<HTTPClient, IOException> send = send(method, url, body, headers);
		AsyncSupplier<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> result = new AsyncSupplier<>();
		send.onDone(client -> client.receiveResponseHeader().onDone(response -> {
			if ((response.getStatusCode() / 100) != 2) {
				result.unblockError(new HTTPResponseError(
					response.getStatusCode(), response.getStatusMessage()
				));
				client.close();
				return;
			}
			IOInMemoryOrFile io = new IOInMemoryOrFile(1024 * 1024, Task.PRIORITY_NORMAL, url);
			OutputToInput output = new OutputToInput(io, url);
			client.receiveBody(response, output, 64 * 1024).onDone(() -> {
				output.endOfData();
				client.close();
			});
			result.unblockSuccess(new Pair<>(response, output));
		}, result), result);
		return result;
	}

	/** Send an HTTP request, with a body and headers, then receive the response headers,
	 * and start to receive the body (but the body may not yet fully received). */
	public static AsyncSupplier<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> sendAndReceive(
		HTTPRequest.Method method, String url, MimeMessage message
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncSupplier<HTTPClient, IOException> send = send(method, url, message);
		AsyncSupplier<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> result = new AsyncSupplier<>();
		send.onDone(client -> client.receiveResponseHeader().onDone(response -> {
			if ((response.getStatusCode() / 100) != 2) {
				result.unblockError(new HTTPResponseError(
					response.getStatusCode(), response.getStatusMessage()
				));
				client.close();
				return;
			}
			IOInMemoryOrFile io = new IOInMemoryOrFile(1024 * 1024, Task.PRIORITY_NORMAL, url);
			OutputToInput output = new OutputToInput(io, url);
			client.receiveBody(response, output, 64 * 1024).onDone(() -> {
				output.endOfData();
				client.close();
			});
			result.unblockSuccess(new Pair<>(response, output));
		}, result), result);
		return result;
	}
	
	/** Send an HTTP request, with an optional body and headers, then receive the response headers,
	 * and receive the full body before to unblock the returned AsyncWork. */
	public static AsyncSupplier<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> sendAndReceiveFully(
		HTTPRequest.Method method, String url, IO.Readable body, MimeHeader... headers
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncSupplier<HTTPClient, IOException> send = send(method, url, body, headers);
		AsyncSupplier<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> result = new AsyncSupplier<>();
		send.onDone(client -> client.receiveResponseHeader().onDone(response -> {
			if ((response.getStatusCode() / 100) != 2) {
				result.unblockError(new HTTPResponseError(
					response.getStatusCode(), response.getStatusMessage()
				));
				client.close();
				return;
			}
			IOInMemoryOrFile io = new IOInMemoryOrFile(1024 * 1024, Task.PRIORITY_NORMAL, url);
			OutputToInput output = new OutputToInput(io, url);
			client.receiveBody(response, output, 64 * 1024).onDone(() -> {
				output.endOfData();
				result.unblockSuccess(new Pair<>(response, output));
			}, result);
			result.onDone(client::close);
		}, result), result);
		return result;
	}

	/** Send an HTTP request, with a body and headers, then receive the response headers,
	 * and receive the full body before to unblock the returned AsyncWork. */
	public static AsyncSupplier<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> sendAndReceiveFully(
		HTTPRequest.Method method, String url, MimeMessage message
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncSupplier<HTTPClient, IOException> send = send(method, url, message);
		AsyncSupplier<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> result = new AsyncSupplier<>();
		send.onDone(client -> client.receiveResponseHeader().onDone(response -> {
			if ((response.getStatusCode() / 100) != 2) {
				result.unblockError(new HTTPResponseError(
					response.getStatusCode(), response.getStatusMessage()
				));
				client.close();
				return;
			}
			IOInMemoryOrFile io = new IOInMemoryOrFile(1024 * 1024, Task.PRIORITY_NORMAL, url);
			OutputToInput output = new OutputToInput(io, url);
			client.receiveBody(response, output, 64 * 1024).onDone(() -> {
				output.endOfData();
				result.unblockSuccess(new Pair<>(response, output));
			}, result);
			result.onDone(client::close);
		}, result), result);
		return result;
	}
	
	/**
	 * Send a GET request to the given URL, and receive the headers in the HTTPResponse, but not the body.
	 * The body can then be read using the returned HTTPClient and HTTPResponse.<br/>
	 * If maxRedirects is positive, the redirections are automatically handled.<br/>
	 * The given headers are added to the request, including the subsequent requests in case of redirection.
	 */
	public static AsyncSupplier<Pair<HTTPClient, HTTPResponse>, IOException> sendGET(
		String url, int maxRedirects, MimeHeader... headers
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncSupplier<HTTPClient, IOException> send = send(HTTPRequest.Method.GET, url, (IO.Readable)null, headers);
		AsyncSupplier<Pair<HTTPClient, HTTPResponse>, IOException> result = new AsyncSupplier<>();
		MutableInteger redirectCount = new MutableInteger(0);
		send.onDone(client -> client.receiveResponseHeader().onDone(
			response -> {
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
						sendGET(location, maxRedirects - 1, headers).forward(result);
					} catch (Exception e) {
						result.error(IO.error(e));
					}
				} catch (URISyntaxException e) {
					result.error(new IOException("Invalid redirect location: " + location, e));
				}
			}, result), result);
		return result;
	}
	
	
	/**
	 * Send a GET request, and receive the response into the given file.
	 */
	public static AsyncSupplier<Pair<HTTPResponse, FileIO.ReadWrite>, IOException> GET(
		String url, File file, int maxRedirects, MimeHeader... headers
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncSupplier<Pair<HTTPResponse, FileIO.ReadWrite>, IOException> result = new AsyncSupplier<>();
		sendGET(url, maxRedirects, headers).onDone(p -> {
			HTTPClient client = p.getValue1();
			HTTPResponse response = p.getValue2();
			if ((response.getStatusCode() / 100) != 2) {
				result.unblockError(new HTTPResponseError(response.getStatusCode(), response.getStatusMessage()));
				client.close();
				return;
			}
			FileIO.ReadWrite io = new FileIO.ReadWrite(file, Task.PRIORITY_NORMAL);
			client.receiveBody(response, io, 64 * 1024).onDone(
				() -> {
					AsyncSupplier<Long, IOException> seek = io.seekAsync(SeekType.FROM_BEGINNING, 0);
					seek.onDone(() -> result.unblockSuccess(new Pair<>(response, io)), result);
					client.close();
				},
				result
			);
			result.onErrorOrCancel(() -> {
				client.close();
				try { io.close(); } catch (Exception t) { /* ignore */ }
				file.delete();
			});
		}, result);
		return result;
	}

	/**
	 * Send a GET request, and receive the response.
	 * The returned IO is not yet filled but will be.
	 */
	public static AsyncSupplier<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> GET(
		String url, int maxRedirects, MimeHeader... headers
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncSupplier<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> result = new AsyncSupplier<>();
		sendGET(url, maxRedirects, headers).onDone(p -> {
			HTTPClient client = p.getValue1();
			HTTPResponse response = p.getValue2();
			if ((response.getStatusCode() / 100) != 2) {
				result.unblockError(new HTTPResponseError(response.getStatusCode(), response.getStatusMessage()));
				client.close();
				return;
			}
			IOInMemoryOrFile io = new IOInMemoryOrFile(1024 * 1024, Task.PRIORITY_NORMAL, url);
			OutputToInput output = new OutputToInput(io, url);
			Async<IOException> receive = client.receiveBody(response, output, 64 * 1024);
			receive.onDone(output::endOfData, output::signalErrorBeforeEndOfData,
				cancel -> output.signalErrorBeforeEndOfData(IO.errorCancelled(cancel)));
			receive.onDone(client::close);
			result.unblockSuccess(new Pair<>(response, output));
		}, result);
		return result;
	}

	/**
	 * Send a GET request, and receive the response.
	 * The returned IO is completely filled.
	 */
	public static AsyncSupplier<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> GETfully(
		String url, int maxRedirects, MimeHeader... headers
	) throws URISyntaxException, GeneralSecurityException, UnsupportedHTTPProtocolException {
		AsyncSupplier<Pair<HTTPResponse, IO.Readable.Seekable>, IOException> result = new AsyncSupplier<>();
		sendGET(url, maxRedirects, headers).onDone(p -> {
			HTTPClient client = p.getValue1();
			HTTPResponse response = p.getValue2();
			if ((response.getStatusCode() / 100) != 2) {
				result.unblockError(new HTTPResponseError(response.getStatusCode(), response.getStatusMessage()));
				client.close();
				return;
			}
			IOInMemoryOrFile io = new IOInMemoryOrFile(1024 * 1024, Task.PRIORITY_NORMAL, url);
			OutputToInput output = new OutputToInput(io, url);
			client.receiveBody(response, output, 64 * 1024).onDone(() -> {
				output.endOfData();
				result.unblockSuccess(new Pair<>(response, output));
			}, result);
			result.onDone(() -> {
				client.close();
				if (!result.isSuccessful())
					try { output.close(); } catch (Exception t) { /* ignore */ }
			});
		}, result);
		return result;
	}

	/** Download from a URL, and return immediately a IO.Readable. */
	public static IO.Readable download(String url, int maxRedirects, int maxInMemory, MimeHeader... headers)
	throws UnsupportedHTTPProtocolException, URISyntaxException, GeneralSecurityException {
		IOInMemoryOrFile io = new IOInMemoryOrFile(maxInMemory, Task.PRIORITY_NORMAL, url);
		OutputToInput output = new OutputToInput(io, url);
		AsyncSupplier<Pair<HTTPClient, HTTPResponse>, IOException> send = sendGET(url, maxRedirects, headers);
		send.onDone(p -> {
			if (send.hasError()) {
				output.signalErrorBeforeEndOfData(send.getError());
				return;
			}
			HTTPClient client = p.getValue1();
			HTTPResponse response = p.getValue2();
			if ((response.getStatusCode() / 100) != 2) {
				output.signalErrorBeforeEndOfData(new HTTPResponseError(response.getStatusCode(), response.getStatusMessage()));
				client.close();
				return;
			}
			Async<IOException> receive = client.receiveBody(response, output, 64 * 1024);
			receive.onDone(output::endOfData, output::signalErrorBeforeEndOfData,
				cancel -> output.signalErrorBeforeEndOfData(IO.errorCancelled(cancel)));
			receive.onDone(client::close);
		});
		return output;
	}

}
