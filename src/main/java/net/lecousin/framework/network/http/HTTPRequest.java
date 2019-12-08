package net.lecousin.framework.network.http;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.lecousin.framework.io.IO;
import net.lecousin.framework.network.http.exception.InvalidHTTPCommandLineException;
import net.lecousin.framework.network.http.exception.InvalidHTTPMethodException;
import net.lecousin.framework.network.http.exception.UnsupportedHTTPProtocolException;
import net.lecousin.framework.network.mime.MimeHeader;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.UnprotectedString;
import net.lecousin.framework.util.UnprotectedStringBuffer;

/** HTTP request. */
public class HTTPRequest extends HTTPMessage {
	
	/** HTTP method. */
	public enum Method {
		OPTIONS,
		GET,
		HEAD,
		POST,
		PUT,
		DELETE,
		TRACE,
		CONNECT,
	}
	
	// Constructors
	
	/** Constructor. */
	public HTTPRequest() {
		setProtocol(Protocol.HTTP_1_1);
	}
	
	/** Constructor. */
	public HTTPRequest(Method method) {
		this.method = method;
		setProtocol(Protocol.HTTP_1_1);
	}
	
	/** Constructor. */
	public HTTPRequest(Method method, String path) {
		this.method = method;
		this.path = path;
		setProtocol(Protocol.HTTP_1_1);
	}
	
	// Command line
	
	private Method method = null;
	private String path = null;
	private HashMap<String,String> parameters = null;
	
	// Command line getters and setters
	
	public Method getMethod() {
		return method;
	}
	
	public String getPath() {
		return path;
	}
	
	public Map<String,String> getParameters() {
		return parameters;
	}
	
	/** Return a query parameter or null if not set. */
	public String getParameter(String name) {
		return parameters != null ? parameters.get(name) : null;
	}
	
	public boolean isCommandSet() {
		return method != null && path != null && getProtocol() != null;
	}
	
	/** Set the method to use. */
	public HTTPRequest setMethod(Method method) {
		this.method = method;
		return this;
	}
	
	/** Set the path. */
	public HTTPRequest setPath(String path) {
		this.path = path;
		return this;
	}
	
	/** Set the path. */
	public HTTPRequest setPathAndQueryString(String pathAndQuery) {
		path = pathAndQuery;
		parameters = new HashMap<>();
		int i = path.indexOf('?');
		if (i >= 0) {
			setQueryString(path.substring(i + 1));
			if (i == 0)
				path = "";
			else
				path = path.substring(0, i);
		}

		return this;
	}
	
	/** Set the command line. */
	public HTTPRequest setCommand(Method method, String path, Protocol protocol) {
		this.method = method;
		this.path = path;
		setProtocol(protocol);
		return this;
	}
	
	/** Set the command line. */
	public HTTPRequest setCommand(String commandLine)
	throws InvalidHTTPCommandLineException, InvalidHTTPMethodException, UnsupportedHTTPProtocolException {
		int i = commandLine.indexOf(' ');
		if (i <= 0) throw new InvalidHTTPCommandLineException(commandLine);
		try { method = Method.valueOf(commandLine.substring(0, i)); }
		catch (IllegalArgumentException e) {
			throw new InvalidHTTPMethodException(commandLine.substring(0, i));
		}
		commandLine = commandLine.substring(i + 1);
		i = commandLine.indexOf(' ');
		if (i <= 0) throw new InvalidHTTPCommandLineException(commandLine);
		setPathAndQueryString(commandLine.substring(0, i));
		setProtocol(Protocol.from(commandLine.substring(i + 1).trim()));
		if (getProtocol() == null) throw new UnsupportedHTTPProtocolException(commandLine.substring(i + 1).trim());
		
		return this;
	}
	
	/** Set the query string in the path. */
	public HTTPRequest setQueryString(String s) {
		if (s == null || s.length() == 0)
			return this;
		String[] params = s.split("&");
		if (parameters == null)
			parameters = new HashMap<>();
		for (String p : params) {
			int i = p.indexOf('=');
			if (i < 0)
				parameters.put(p, "");
			else
				try {
					parameters.put(
						URLDecoder.decode(p.substring(0, i), StandardCharsets.UTF_8.name()),
						URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8.name())
					);
				} catch (UnsupportedEncodingException e) {
					// cannot happen... utf-8 is always supported
				}
		}
		return this;
	}
	
	/** Add a query parameter in the URL. */
	public HTTPRequest addParameter(String name, String value) {
		if (parameters == null)
			parameters = new HashMap<>();
		parameters.put(name, value);
		return this;
	}
	
	/** Set the path and query string from the given URI. */
	public HTTPRequest setURI(URI uri) {
		return setPath(uri.getRawPath()).setQueryString(uri.getRawQuery());
	}
	
	/** Generate path with query string. */
	public void generateFullPath(Appendable s) {
		try {
			s.append(path);
			if (parameters == null || parameters.isEmpty())
				return;
			s.append('?');
			boolean first = true;
			for (Map.Entry<String,String> param : parameters.entrySet()) {
				if (first) first = false;
				else s.append('&');
				s.append(URLEncoder.encode(param.getKey(), StandardCharsets.UTF_8.name()));
				s.append('=');
				s.append(URLEncoder.encode(param.getValue(), StandardCharsets.UTF_8.name()));
			}
		} catch (IOException e) {
			// does not happen if used with StringBuilder or IString
		}
	}
	
	/** Generate the command line. */
	public void generateCommandLine(Appendable s) {
		try {
			if (method == null)
				s.append("NULL ");
			else
				s.append(method.toString()).append(' ');
			generateFullPath(s);
			s.append(' ').append(getProtocol() != null ? getProtocol().getName() : Protocol.HTTP_1_1.getName());
		} catch (IOException e) {
			// does not happen if used with StringBuilder or IString
		}
	}
	
	/** Generate the command line. */
	public String generateCommandLine() {
		StringBuilder s = new StringBuilder(128);
		generateCommandLine(s);
		return s.toString();
	}
	
	/** Generate command line and headers into a string. */
	public UnprotectedStringBuffer generateCommandLineAndHeaders() {
		UnprotectedStringBuffer s = new UnprotectedStringBuffer(new UnprotectedString(512));
		generateCommandLine(s);
		s.append("\r\n");
		getMIME().appendHeadersTo(s);
		s.append("\r\n");
		return s;
	}
	
	@Override
	public String toString() {
		return generateCommandLineAndHeaders().toString();
	}
	
	// Content
	
	/** Set multiple headers to the MIME message. */
	public HTTPRequest setHeaders(MimeHeader... headers) {
		for (MimeHeader h : headers)
			getMIME().setHeader(h);
		return this;
	}
	
	/** Set headers and body from the given message. */
	public HTTPRequest setHeadersAndContent(MimeMessage message) {
		for (MimeHeader header : message.getHeaders())
			getMIME().setHeader(header);
		getMIME().setBodyToSend(message.getBodyToSend());
		return this;
	}
	
	/** Set the body to send. */
	public HTTPRequest setBody(IO.Readable body) {
		getMIME().setBodyToSend(body);
		return this;
	}
	
	// Utilities
	
	/** Set the method to POST and body to send. */
	public HTTPRequest post(IO.Readable body) {
		setMethod(Method.POST);
		getMIME().setBodyToSend(body);
		return this;
	}
	
	/** Set the method to POST and body to send. */
	public HTTPRequest post(MimeMessage body) {
		return setMethod(Method.POST).setHeadersAndContent(body);
	}
	
	/** Return true if a body is expected to be sent. */
	public boolean isExpectingBody() {
		if (Method.GET.equals(method)) return false;
		Long size = getMIME().getContentLength();
		if (size != null)
			return size.longValue() != 0;
		String s = getMIME().getFirstHeaderRawValue(MimeMessage.TRANSFER_ENCODING);
		if (s == null) s = getMIME().getFirstHeaderRawValue(MimeMessage.CONTENT_TRANSFER_ENCODING);
		return (s != null && s.length() != 0);
	}
	
	/** Return true if the connection should be persistent. */
	public boolean isConnectionPersistent() {
		if (Protocol.HTTP_1_1.equals(getProtocol())) {
			String s = getMIME().getFirstHeaderRawValue(MimeMessage.CONNECTION);
			return s == null || !s.equalsIgnoreCase("close");
		}
		return false;
	}
	
	// Convenient methods
	
	/** Return the requested cookie or null. */
	public String getCookie(String name) {
		try {
			for (ParameterizedHeaderValue h : getMIME().getHeadersValues("cookie", ParameterizedHeaderValue.class)) {
				String value = h.getParameter(name);
				if (value != null)
					return value;
			}
		} catch (Exception e) {
			// ignore
		}
		return null;
	}

	/** Return the list of values for the given cookie. */
	public List<String> getCookies(String name) {
		List<String> values = new ArrayList<>();
		try {
			for (ParameterizedHeaderValue h : getMIME().getHeadersValues("cookie", ParameterizedHeaderValue.class)) {
				for (Pair<String, String> p : h.getParameters())
					if (p.getValue1().equals(name))
						values.add(p.getValue2());
			}
		} catch (Exception e) {
			// ignore
		}
		return values;
	}
	
}
