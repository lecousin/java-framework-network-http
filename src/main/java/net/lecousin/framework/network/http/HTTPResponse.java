package net.lecousin.framework.network.http;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.MimeUtil;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/** HTTP Response. */
public class HTTPResponse {
	
	public static final Log logger = LogFactory.getLog(HTTPResponse.class);
	
	public static final String SERVER_HEADER = "Server";
	
	private int statusCode = -1;
	private String statusMessage = null;
	private HTTPRequest.Protocol protocol = null;
	private MimeMessage mime = new MimeMessage();
	private boolean forceClose = false;
	
	public void setProtocol(HTTPRequest.Protocol protocol) {
		this.protocol = protocol;
	}
	
	public HTTPRequest.Protocol getProtocol() {
		return protocol;
	}
	
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
	
	public MimeMessage getMIME() { return mime; }
	
	public void setMIME(MimeMessage mime) {
		this.mime = mime;
	}
	
	/** Set the Content-Type header. */
	public void setRawContentType(String type) {
		mime.setHeaderRaw(MimeMessage.CONTENT_TYPE, type);
	}
	
	/** Set a header. */
	public void setHeaderRaw(String name, String value) {
		mime.setHeaderRaw(name, value);
	}
	
	/** Add a value to a header. */
	public void addHeaderRaw(String headerName, String value) {
		mime.addHeaderRaw(headerName, value);
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
	
	/** Set headers to indicate that the response must not be cached. */
	public void noCache() {
		mime.setHeaderRaw("Cache-Control", "no-cache,no-store");
		mime.setHeaderRaw("Pragma", "no-cache");
		mime.setHeaderRaw("Expires", DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.EPOCH.atZone(ZoneId.of("GMT"))));
	}
	
	/** Set headers to indicate that the response can be cached for the given duration in milliseconds. */
	public void publicCache(Long maxAge) {
		if (maxAge != null) {
			mime.setHeaderRaw("Cache-Control", "public,max-age=" + maxAge);
			mime.setHeaderRaw("Expires",DateTimeFormatter.RFC_1123_DATE_TIME.format(
				Instant.ofEpochMilli(System.currentTimeMillis() + maxAge.longValue()).atZone(ZoneId.of("GMT"))));
		} else
			mime.setHeaderRaw("Cache-Control", "public");
	}
	
	/** Return true if a body is expected to be received in this response. */
	public boolean isBodyExpected() {
		if (statusCode == 204) return false;
		if (statusCode == 205) return false;
		Long length = mime.getContentLength();
		if (length != null && length.longValue() == 0) return false;
		return true;
	}
	
	/** Set status code to 301 with the given location. */
	public void redirectPerm(String location) {
		setStatus(HttpURLConnection.HTTP_MOVED_PERM);
		setHeaderRaw("Location", location);
	}
	
	public void setForceClose(boolean forceClose) {
		this.forceClose = forceClose;
	}
	
	/** Return true if it is requested to close the connection once the response is sent. */
	public boolean forceClose() {
		return forceClose;
	}
	
	/** Receive a response from a server, by using the given TCPClient. */
	public static AsyncWork<HTTPResponse, IOException> receive(TCPClient client, int timeout) {
		AsyncWork<HTTPResponse, IOException> result = new AsyncWork<HTTPResponse, IOException>();
		if (logger.isTraceEnabled())
			logger.trace("Receiving status line...");
		AsyncWork<ByteArrayIO,IOException> statusLine = client.getReceiver().readUntil((byte)'\n', 1024, timeout);
		statusLine.listenInline(
			(line) -> {
				String s = line.getAsString(StandardCharsets.US_ASCII);
				if (logger.isTraceEnabled())
					logger.trace("Status line received: " + s);
				int i = s.indexOf(' ');
				if (i < 0) {
					result.unblockError(new IOException("Invalid HTTP status line: " + s));
					return;
				}
				HTTPResponse response = new HTTPResponse();
				response.setProtocol(HTTPRequest.Protocol.from(s.substring(0, i)));
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
				SynchronizationPoint<IOException> header = response.mime.readHeader(client, timeout);
				header.listenInline(
					() -> { result.unblockSuccess(response); },
					result
				);
			},
			result
		);
		return result;
	}
	
}
