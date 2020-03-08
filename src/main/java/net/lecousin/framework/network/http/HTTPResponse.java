package net.lecousin.framework.network.http;

import java.net.HttpURLConnection;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.mime.MimeException;
import net.lecousin.framework.network.mime.MimeUtil;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValues;

/** HTTP Response. */
public class HTTPResponse extends HTTPMessage<HTTPResponse> {
	
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
	
	public void setStatusMessage(String message) {
		this.statusMessage = message;
	}
	
	public int getStatusCode() { return statusCode; }
	
	public String getStatusMessage() { return statusMessage; }
	
	/** Add a cookie.
	 * @param expiration 0 for none
	 */
	public void addCookie(String name, String value, long expiration, String path, String domain, boolean secure, boolean httpOnly) {
		StringBuilder s = new StringBuilder();
		s.append(name).append('=');
		s.append(MimeUtil.encodeHeaderValueWithUTF8(value));
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
		getHeaders().addRawValue("Set-Cookie", s.toString());
	}
	
	/** Search for a value in Set-Cookie header.
	 * @param name name of the cookie to search
	 * @throws Exception in case the Set-Cookie header cannot be parsed
	 */
	public String getCookie(String name) throws MimeException {
		for (ParameterizedHeaderValues v : getHeaders().getValues("Set-Cookie", ParameterizedHeaderValues.class)) {
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
		setHeader(HTTPConstants.Headers.Response.CACHE_CONTROL, "no-cache,no-store");
		setHeader(HTTPConstants.Headers.Response.PRAGMA, "no-cache");
		setHeader(HTTPConstants.Headers.Response.EXPIRES,
			DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.EPOCH.atZone(ZoneId.of("GMT"))));
	}
	
	/** Set headers to indicate that the response can be cached for the given duration in milliseconds. */
	public void publicCache(Long maxAge) {
		if (maxAge != null) {
			setHeader(HTTPConstants.Headers.Response.CACHE_CONTROL, "public,max-age=" + maxAge);
			setHeader(HTTPConstants.Headers.Response.EXPIRES,DateTimeFormatter.RFC_1123_DATE_TIME.format(
				Instant.ofEpochMilli(System.currentTimeMillis() + maxAge.longValue()).atZone(ZoneId.of("GMT"))));
		} else {
			setHeader(HTTPConstants.Headers.Response.CACHE_CONTROL, "public");
		}
	}
	
	/** Return true if a body is expected to be received in this response. */
	public boolean isBodyExpected() {
		if (statusCode == 204) return false;
		if (statusCode == 205) return false;
		Long length = getHeaders().getContentLength();
		return length == null || length.longValue() > 0;
	}
	
	/** Return statusCode / 100. */
	public int getResultType() {
		return statusCode / 100;
	}
	
	/** Return true is statusCode is 2xx. */
	public boolean isSuccess() {
		return getResultType() == 2;
	}
	
	/** If statusCode is 2xx does nothing, else throw a HTTPResposneError. */
	public void checkSuccess() throws HTTPResponseError {
		if (isSuccess()) return;
		throw new HTTPResponseError(statusCode, statusMessage);
	}
	
	/** Set status code to 301 with the given location. */
	public void redirectPerm(String location) {
		setStatus(HttpURLConnection.HTTP_MOVED_PERM);
		setHeader("Location", location);
	}
	
}
