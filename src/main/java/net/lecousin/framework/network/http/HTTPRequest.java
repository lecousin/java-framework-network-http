package net.lecousin.framework.network.http;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.lecousin.framework.network.AttributesContainer;
import net.lecousin.framework.network.http.exception.InvalidHTTPCommandLineException;
import net.lecousin.framework.network.http.exception.InvalidHTTPMethodException;
import net.lecousin.framework.network.http.exception.UnsupportedHTTPProtocolException;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.util.IString;
import net.lecousin.framework.util.Pair;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/** HTTP request. */
public class HTTPRequest implements AttributesContainer {
	
	public static Log logger = LogFactory.getLog(HTTPRequest.class);

	/** HTTP method. */
	public static enum Method {
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
	public static enum Protocol {
		HTTP_1_1("HTTP/1.1"),
		HTTP_1_0("HTTP/1.0");
		private Protocol(String name) {
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
	public HTTPRequest(Method method, String path) {
		this.method = method;
		this.path = path;
	}
	
	// Attributes
	
	private Map<String,Object> attributes = new HashMap<>();
	
	@Override
	public void setAttribute(String name, Object value) {
		attributes.put(name, value);
	}
	
	@Override
	public Object getAttribute(String name) {
		return attributes.get(name);
	}
	
	@Override
	public Object removeAttribute(String name) {
		return attributes.remove(name);
	}

	@Override
	public boolean hasAttribute(String name) {
		return attributes.containsKey(name);
	}
	
	// Command line
	
	private Method method = null;
	private String path = null;
	private HashMap<String,String> parameters = null;
	private Protocol protocol = Protocol.HTTP_1_1;
	
	// Command line getters and setters
	
	public Method getMethod() { return method; }
	
	public String getPath() { return path; }
	
	public Map<String,String> getParameters() { return parameters; }
	
	public String getParameter(String name) { return parameters != null ? parameters.get(name) : null; }
	
	public Protocol getProtocol() { return protocol; }
	
	public boolean isCommandSet() { return method != null && path != null && protocol != null; }
	
	public void setMethod(Method method) { this.method = method; }
	
	public void setPath(String path) { this.path = path; }
	
	public void setProtocol(Protocol protocol) { this.protocol = protocol; }
	
	/** Set the command line. */
	public void setCommand(Method method, String path, Protocol protocol) {
		this.method = method;
		this.path = path;
		this.protocol = protocol;
	}
	
	/** Set the command line. */
	public void setCommand(String commandLine)
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
		path = commandLine.substring(0, i);
		protocol = Protocol.from(commandLine.substring(i + 1).trim());
		if (protocol == null) throw new UnsupportedHTTPProtocolException(commandLine.substring(i + 1).trim());
		
		parameters = new HashMap<>();
		i = path.indexOf('?');
		if (i >= 0) {
			setQueryString(path.substring(i + 1));
			if (i == 0)
				path = "";
			else
				path = path.substring(0, i);
		}
	}
	
	/** Set the query string in the path. */
	public void setQueryString(String s) {
		if (s == null || s.length() == 0)
			return;
		String[] params = s.split("&");
		if (parameters == null)
			parameters = new HashMap<>();
		for (String p : params) {
			int i = p.indexOf('=');
			if (i < 0)
				parameters.put(p, "");
			else
				try { parameters.put(URLDecoder.decode(p.substring(0, i), "UTF-8"), URLDecoder.decode(p.substring(i + 1), "UTF-8")); }
				catch (UnsupportedEncodingException e) {
					// cannot happen... utf-8 is always supported
				}
		}
	}
	
	/** Generate path with query string. */
	public void generateFullPath(StringBuilder s) {
		s.append(path);
		if (parameters == null || parameters.isEmpty())
			return;
		s.append('?');
		boolean first = true;
		for (Map.Entry<String,String> param : parameters.entrySet()) {
			if (first) first = false;
			else s.append('&');
			try {
				s.append(URLEncoder.encode(param.getKey(), "UTF-8"));
				s.append('=');
				s.append(URLEncoder.encode(param.getValue(), "UTF-8"));
			} catch (UnsupportedEncodingException e) {
				// cannot happen... utf-8 is always supported
			}
		}
	}
	
	/** Generate path with query string. */
	public void generateFullPath(IString s) {
		s.append(path);
		if (parameters == null || parameters.isEmpty())
			return;
		s.append('?');
		boolean first = true;
		for (Map.Entry<String,String> param : parameters.entrySet()) {
			if (first) first = false;
			else s.append('&');
			try {
				s.append(URLEncoder.encode(param.getKey(), "UTF-8"));
				s.append('=');
				s.append(URLEncoder.encode(param.getValue(), "UTF-8"));
			} catch (UnsupportedEncodingException e) {
				// cannot happen... utf-8 is always supported
			}
		}
	}
	
	/** Generate the command line. */
	public void generateCommandLine(StringBuilder s) {
		if (method == null)
			s.append("NULL ");
		else
			s.append(method.toString()).append(' ');
		generateFullPath(s);
		s.append(' ').append(protocol.getName());
	}
	
	/** Generate the command line. */
	public void generateCommandLine(IString s) {
		if (method == null)
			s.append("NULL ");
		else
			s.append(method.toString()).append(' ');
		generateFullPath(s);
		s.append(' ').append(protocol.getName());
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
	
	// Utilities
	
	/** Return true if a body is expected to be sent. */
	public boolean isExpectingBody() {
		if (Method.GET.equals(method)) return false;
		Long size = mime.getContentLength();
		if (size != null) {
			if (size.longValue() == 0) return false;
			return true;
		}
		String s = mime.getFirstHeaderRawValue(MimeMessage.TRANSFER_ENCODING);
		if (s == null) s = mime.getFirstHeaderRawValue(MimeMessage.CONTENT_TRANSFER_ENCODING);
		if (s == null || s.length() == 0)
			return false;
		return true;
	}
	
	/** Return true if the connection should be persistent. */
	public boolean isConnectionPersistent() {
		switch (protocol) {
		case HTTP_1_1: {
			String s = mime.getFirstHeaderRawValue(MimeMessage.CONNECTION);
			if (s == null) return true;
			if (s.equalsIgnoreCase("close")) return false;
			return true;
		}
		default:
			return false;
		}
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
