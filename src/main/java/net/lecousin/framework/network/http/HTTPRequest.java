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
import java.util.function.Supplier;

import net.lecousin.framework.io.IO;
import net.lecousin.framework.network.AbstractAttributesContainer;
import net.lecousin.framework.network.http.exception.InvalidHTTPCommandLineException;
import net.lecousin.framework.network.http.exception.InvalidHTTPMethodException;
import net.lecousin.framework.network.http.exception.UnsupportedHTTPProtocolException;
import net.lecousin.framework.network.mime.MimeHeader;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.util.Pair;

/** HTTP request. */
public class HTTPRequest extends AbstractAttributesContainer {
	
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
	
	/** HTTP protocol version. */
	public enum Protocol {
		HTTP_1_1("HTTP/1.1"),
		HTTP_1_0("HTTP/1.0");
		
		Protocol(String name) {
			this.name = name;
		}
		
		private String name;
		
		public String getName() { return name; }
		
		/** from a string. */
		public static Protocol from(String s) {
			for (Protocol p : Protocol.values())
				if (p.getName().equals(s))
					return p;
			return null;
		}
	}
	
	public static final String HEADER_HOST = "Host";
	public static final String HEADER_USER_AGENT = "User-Agent";
	public static final String HEADER_CONNECTION = "Connection";
	
	// Constructors
	
	/** Constructor. */
	public HTTPRequest() {
	}
	
	/** Constructor. */
	public HTTPRequest(Method method) {
		this.method = method;
	}
	
	/** Constructor. */
	public HTTPRequest(Method method, String path) {
		this.method = method;
		this.path = path;
	}
	
	// Command line
	
	private Method method = null;
	private String path = null;
	private HashMap<String,String> parameters = null;
	private Protocol protocol = Protocol.HTTP_1_1;
	
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
	
	public Protocol getProtocol() {
		return protocol;
	}
	
	public boolean isCommandSet() {
		return method != null && path != null && protocol != null;
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
	
	/** Set the protocol version. */
	public HTTPRequest setProtocol(Protocol protocol) {
		this.protocol = protocol;
		return this;
	}
	
	/** Set the command line. */
	public HTTPRequest setCommand(Method method, String path, Protocol protocol) {
		this.method = method;
		this.path = path;
		this.protocol = protocol;
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
		protocol = Protocol.from(commandLine.substring(i + 1).trim());
		if (protocol == null) throw new UnsupportedHTTPProtocolException(commandLine.substring(i + 1).trim());
		
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
			s.append(' ').append(protocol != null ? protocol.getName() : Protocol.HTTP_1_1.getName());
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
	
	// Content
	
	private MimeMessage mime = new MimeMessage();
	
	public MimeMessage getMIME() { return mime; }
	
	/** Set multiple headers to the MIME message. */
	public HTTPRequest setHeaders(MimeHeader... headers) {
		for (MimeHeader h : headers)
			mime.setHeader(h);
		return this;
	}
	
	/** Set headers and body from the given message. */
	public HTTPRequest setHeadersAndContent(MimeMessage message) {
		for (MimeHeader header : message.getHeaders())
			mime.setHeader(header);
		mime.setBodyToSend(message.getBodyToSend());
		return this;
	}
	
	/** Set the body to send. */
	public HTTPRequest setBody(IO.Readable body) {
		mime.setBodyToSend(body);
		return this;
	}
	
	// Trailer headers
	
	private Map<String, Supplier<String>> trailerHeaderSuppliers = null;
	
	/** Add a trailer MIME header. */
	public HTTPRequest addTrailerHeader(String headerName, Supplier<String> supplier) {
		if (trailerHeaderSuppliers == null)
			trailerHeaderSuppliers = new HashMap<>(5);
		trailerHeaderSuppliers.put(headerName, supplier);
		return this;
	}
	
	/** Get the trailer header supplier. */
	public Supplier<List<MimeHeader>> getTrailerHeadersSuppliers() {
		if (trailerHeaderSuppliers == null)
			return null;
		StringBuilder s = new StringBuilder();
		for (String h : trailerHeaderSuppliers.keySet()) {
			if (s.length() > 0)
				s.append(", ");
			s.append(h);
		}
		mime.setHeaderRaw("Trailer", s.toString());
		return () -> {
			List<MimeHeader> headers = new ArrayList<>(trailerHeaderSuppliers.size());
			for (Map.Entry<String, Supplier<String>> entry : trailerHeaderSuppliers.entrySet()) {
				headers.add(new MimeHeader(entry.getKey(), entry.getValue().get()));
			}
			return headers;
		};
	}
	
	// Utilities
	
	/** Set the method to POST and body to send. */
	public HTTPRequest post(IO.Readable body) {
		setMethod(Method.POST);
		mime.setBodyToSend(body);
		return this;
	}
	
	/** Set the method to POST and body to send. */
	public HTTPRequest post(MimeMessage body) {
		return setMethod(Method.POST).setHeadersAndContent(body);
	}
	
	/** Return true if a body is expected to be sent. */
	public boolean isExpectingBody() {
		if (Method.GET.equals(method)) return false;
		Long size = mime.getContentLength();
		if (size != null)
			return size.longValue() != 0;
		String s = mime.getFirstHeaderRawValue(MimeMessage.TRANSFER_ENCODING);
		if (s == null) s = mime.getFirstHeaderRawValue(MimeMessage.CONTENT_TRANSFER_ENCODING);
		return (s != null && s.length() != 0);
	}
	
	/** Return true if the connection should be persistent. */
	public boolean isConnectionPersistent() {
		if (protocol == null) return false;
		if (Protocol.HTTP_1_1.equals(protocol)) {
			String s = mime.getFirstHeaderRawValue(MimeMessage.CONNECTION);
			return s == null || !s.equalsIgnoreCase("close");
		}
		return false;
	}
	
	// Convenient methods
	
	/** Return the requested cookie or null. */
	public String getCookie(String name) {
		try {
			for (ParameterizedHeaderValue h : mime.getHeadersValues("cookie", ParameterizedHeaderValue.class)) {
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
			for (ParameterizedHeaderValue h : mime.getHeadersValues("cookie", ParameterizedHeaderValue.class)) {
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
