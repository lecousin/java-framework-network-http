package net.lecousin.framework.network.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.lecousin.framework.network.AttributesContainer;
import net.lecousin.framework.network.http.exception.InvalidHTTPCommandLineException;
import net.lecousin.framework.network.http.exception.InvalidHTTPMethodException;
import net.lecousin.framework.network.http.exception.UnsupportedHTTPProtocolException;
import net.lecousin.framework.network.mime.MIME;
import net.lecousin.framework.network.mime.MIMEUtil;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

//skip checkstyle: AbbreviationAsWordInName
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
				parameters.put(p.substring(0, i), p.substring(i + 1));
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
			s.append(param.getKey());
			s.append('=');
			s.append(param.getValue());
		}
	}
	
	/** Generate the command line. */
	public void generateCommandLine(StringBuilder s) {
		s.append(method.toString()).append(' ');
		generateFullPath(s);
		s.append(' ').append(protocol.getName());
	}
	
	/** Generate the command line. */
	public String generateCommandLine() {
		StringBuilder s = new StringBuilder();
		generateCommandLine(s);
		return s.toString();
	}
	
	// Content
	
	private MIME mime = new MIME();
	
	public MIME getMIME() { return mime; }
	
	// Utilities
	
	/** Return true if a body is expected to be sent. */
	public boolean isExpectingBody() {
		if (Method.GET.equals(method)) return false;
		String s = mime.getHeaderSingleValue(MIME.CONTENT_LENGTH);
		if (s != null) {
			if ("0".equals(s)) return false;
			return true;
		}
		s = mime.getHeaderSingleValue(MIME.TRANSFER_ENCODING);
		if (s == null) s = mime.getHeaderSingleValue(MIME.CONTENT_TRANSFER_ENCODING);
		if (s == null || s.length() == 0)
			return false;
		return true;
	}
	
	/** Return true if the connection should be persistent. */
	public boolean isConnectionPersistent() {
		switch (protocol) {
		case HTTP_1_1: {
			String s = mime.getHeaderSingleValue(MIME.CONNECTION);
			if (s == null) return true;
			if (s.equalsIgnoreCase("close")) return false;
			return true;
		}
		default:
			return false;
		}
	}
	
	// Convenient methods
	
	/**
	 * Convenient method, equivalent to getMIME().getHeaderSingleValue(headerName)
	 */
	public String getHeader(String headerName) {
		return mime.getHeaderSingleValue(headerName);
	}
	
	/** Return the requested cookie or null. */
	public String getCookie(String name) {
		List<String> lines = mime.getHeaderValues("cookie");
		if (lines == null) return null;
		for (String line : lines) {
			String[] pairs = line.split(";");
			for (String pair : pairs) {
				int i = pair.indexOf('=');
				if (i <= 0) continue;
				String n = pair.substring(0,i).trim();
				if (n.equals(name)) {
					String value = pair.substring(i + 1).trim();
					try { value = MIMEUtil.decodeHeaderRFC2047(value); }
					catch (Throwable t) { /* ignore */ }
					return value;
				}
			}
		}
		return null;
	}

	/** Return the list of values for the given cookie. */
	public List<String> getCookies(String name) {
		List<String> lines = mime.getHeaderValues("cookie");
		if (lines == null) return null;
		List<String> values = new ArrayList<>();
		for (String line : lines) {
			String[] pairs = line.split(";");
			for (String pair : pairs) {
				int i = pair.indexOf('=');
				if (i <= 0) continue;
				String n = pair.substring(0,i).trim();
				if (n.equals(name))
					values.add(pair.substring(i + 1).trim());
			}
		}
		return values;
	}
	
}
