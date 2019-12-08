package net.lecousin.framework.network.http;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.mime.MimeException;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.MimeUtil;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValues;

/** HTTP Response. */
public class HTTPResponse extends HTTPMessage {
	
	private int statusCode = -1;
	private String statusMessage = null;
	
	/** Set the status code and a default status message. */
	public void setStatus(int code) {
		this.statusCode = code;
		if (code < 400)
			this.statusMessage = "OK";
		else
			this.statusMessage = "ERROR";
	}
	
	/** Set the status code and message. */
	public void setStatus(int code, String message) {
		this.statusCode = code;
		this.statusMessage = message;
	}
	
	public int getStatusCode() { return statusCode; }
	
	public String getStatusMessage() { return statusMessage; }
	
	/** Set the Content-Type header. */
	public void setRawContentType(String type) {
		setHeaderRaw(MimeMessage.CONTENT_TYPE, type);
	}
	
	/** Add a cookie.
	 * @param expiration 0 for none
	 */
	public void addCookie(String name, String value, long expiration, String path, String domain, boolean secure, boolean httpOnly) {
		StringBuilder s = new StringBuilder();
		s.append(name).append('=');
		s.append(MimeUtil.encodeUTF8Value(value));
		if (expiration != 0)
			s.append("; Expires=").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(
					Instant.ofEpochMilli(System.currentTimeMillis() + expiration).atZone(ZoneId.of("GMT"))));
		if (path != null)
			s.append("; Path=").append(path);
		if (domain != null)
			s.append("; Domain=").append(domain);
		if (secure)
			s.append("; Secure");
		if (httpOnly)
			s.append("; HttpOnly");
		addHeaderRaw("Set-Cookie", s.toString());
	}
	
	/** Search for a value in Set-Cookie header.
	 * @param name name of the cookie to search
	 * @throws Exception in case the Set-Cookie header cannot be parsed
	 */
	public String getCookie(String name) throws MimeException {
		for (ParameterizedHeaderValues v : getMIME().getHeadersValues("Set-Cookie", ParameterizedHeaderValues.class)) {
			for (ParameterizedHeaderValue value : v.getValues()) {
				String s = value.getParameter(name);
				if (s != null)
					return s;
			}
		}
		return null;
	}
	
	/** Set headers to indicate that the response must not be cached. */
	public void noCache() {
		setHeaderRaw(HTTPConstants.Headers.Response.CACHE_CONTROL, "no-cache,no-store");
		setHeaderRaw(HTTPConstants.Headers.Response.PRAGMA, "no-cache");
		setHeaderRaw(HTTPConstants.Headers.Response.EXPIRES,
			DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.EPOCH.atZone(ZoneId.of("GMT"))));
	}
	
	/** Set headers to indicate that the response can be cached for the given duration in milliseconds. */
	public void publicCache(Long maxAge) {
		if (maxAge != null) {
			setHeaderRaw(HTTPConstants.Headers.Response.CACHE_CONTROL, "public,max-age=" + maxAge);
			setHeaderRaw(HTTPConstants.Headers.Response.EXPIRES,DateTimeFormatter.RFC_1123_DATE_TIME.format(
				Instant.ofEpochMilli(System.currentTimeMillis() + maxAge.longValue()).atZone(ZoneId.of("GMT"))));
		} else {
			setHeaderRaw(HTTPConstants.Headers.Response.CACHE_CONTROL, "public");
		}
	}
	
	/** Return true if a body is expected to be received in this response. */
	public boolean isBodyExpected() {
		if (statusCode == 204) return false;
		if (statusCode == 205) return false;
		Long length = getMIME().getContentLength();
		return length == null || length.longValue() > 0;
	}
	
	/** Set status code to 301 with the given location. */
	public void redirectPerm(String location) {
		setStatus(HttpURLConnection.HTTP_MOVED_PERM);
		setHeaderRaw("Location", location);
	}
	
	
	/** Receive a response from a server, by using the given TCPClient. */
	public static AsyncSupplier<HTTPResponse, IOException> receive(TCPClient client, int timeout) {
		AsyncSupplier<HTTPResponse, IOException> result = new AsyncSupplier<>();
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(HTTPResponse.class);
		if (logger.trace())
			logger.trace("Receiving status line...");
		AsyncSupplier<ByteArrayIO,IOException> statusLine = client.getReceiver().readUntil((byte)'\n', 1024, timeout);
		statusLine.onDone(
			line -> {
				String s = line.getAsString(StandardCharsets.US_ASCII);
				if (logger.trace())
					logger.trace("Status line received: " + s);
				int i = s.indexOf(' ');
				if (i < 0) {
					result.unblockError(new IOException("Invalid HTTP status line: " + s));
					return;
				}
				HTTPResponse response = new HTTPResponse();
				response.setProtocol(Protocol.from(s.substring(0, i)));
				s = s.substring(i + 1);
				i = s.indexOf(' ');
				int code;
				try { code = Integer.parseInt(s.substring(0,i)); }
				catch (NumberFormatException e) {
					result.unblockError(new IOException("Invalid HTTP status code: " + s.substring(0,i)));
					return;
				}
				s = s.substring(i + 1);
				i = s.indexOf('\r');
				if (i >= 0) s = s.substring(0,i);
				i = s.indexOf('\n');
				if (i >= 0) s = s.substring(0,i);
				response.setStatus(code, s);
				Async<IOException> header = response.getMIME().readHeader(client, timeout);
				header.onDone(() -> result.unblockSuccess(response), result);
			},
			result
		);
		return result;
	}
	
}
