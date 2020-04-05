package net.lecousin.framework.network.http;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.lecousin.framework.encoding.URLEncoding;
import net.lecousin.framework.io.data.CharsFromString;
import net.lecousin.framework.network.http1.HTTP1RequestCommandProducer;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.text.ByteArrayStringIso8859;
import net.lecousin.framework.text.ByteArrayStringIso8859Buffer;
import net.lecousin.framework.text.CharArrayStringBuffer;
import net.lecousin.framework.util.Pair;

/** HTTP request. */
public class HTTPRequest extends HTTPMessage<HTTPRequest> {
	
	public static final String METHOD_GET = "GET";
	public static final String METHOD_POST = "POST";
	public static final String METHOD_PUT = "PUT";
	public static final String METHOD_DELETE = "DELETE";
	public static final String METHOD_OPTIONS = "OPTIONS";
	public static final String METHOD_HEAD = "HEAD";
	public static final String METHOD_TRACE = "TRACE";
	public static final String METHOD_CONNECT = "CONNECT";
	
	public static final int MAX_METHOD_LENGTH = 32;
	public static final int MAX_URI_LENGTH = 8000;
	
	protected String method;
	protected ByteArrayStringIso8859Buffer encodedPath;
	protected String decodedPath;
	protected Map<String, String> queryParameters;
	protected ByteArrayStringIso8859Buffer encodedQueryString;
	
	/** Constructor. */
	public HTTPRequest() {
		this.protocolVersion = new HTTPProtocolVersion((byte)1, (byte)1);
	}
	
	/** Constructor copying the given request. */
	public HTTPRequest(HTTPRequest copy) {
		super(copy);
		method = copy.getMethod();
		setEncodedPath(new ByteArrayStringIso8859Buffer(copy.getEncodedPath().copy()));
		setEncodedQueryString(new ByteArrayStringIso8859Buffer(copy.getEncodedQueryString().copy()));
	}
	
	public String getMethod() {
		return method;
	}
	
	/** Set the method. */
	public HTTPRequest setMethod(String method) {
		this.method = method;
		return this;
	}
	
	/** Return the decoded path. */
	public String getDecodedPath() {
		if (decodedPath == null && encodedPath != null)
			decodedPath = URLEncoding.decode(encodedPath.asReadableBytes()).asString();
		return decodedPath;
	}
	
	/** Return the URL encoded path. */
	public ByteArrayStringIso8859Buffer getEncodedPath() {
		if (encodedPath == null && decodedPath != null) {
			encodedPath = new ByteArrayStringIso8859Buffer();
			CharsFromString chars = new CharsFromString(decodedPath);
			URLEncoding.encode(chars, encodedPath.asWritableBytes(), true, true);
		}
		return encodedPath;
	}

	/** Set the path. */
	public HTTPRequest setDecodedPath(String path) {
		this.decodedPath = path;
		this.encodedPath = null;
		return this;
	}

	/** Set the path. */
	public HTTPRequest setEncodedPath(ByteArrayStringIso8859Buffer path) {
		this.encodedPath = path;
		this.decodedPath = null;
		return this;
	}
	
	/** Get a query parameter. */
	public String getQueryParameter(String name) {
		if (queryParameters == null) {
			if (encodedQueryString != null)
				decodeQueryParameters();
			else
				return null;
		}
		return queryParameters.get(name);
	}
	
	/** Return a read-only map of decoded query parameters. */
	public Map<String, String> getQueryParameters() {
		if (queryParameters == null) {
			if (encodedQueryString != null)
				decodeQueryParameters();
			else
				queryParameters = new HashMap<>();
		}
		return Collections.unmodifiableMap(queryParameters);
	}
	
	private void decodeQueryParameters() {
		queryParameters = new HashMap<>();
		int nameStart = 0;
		int nameEnd = 0;
		int valueStart = -1;
		for (int i = 0; i < encodedQueryString.length(); ++i) {
			if (encodedQueryString.charAt(i) == '&') {
				decodeQueryParameter(nameStart, nameEnd, valueStart, i);
				nameStart = i + 1;
				nameEnd = i + 1;
				valueStart = -1;
				continue;
			}
			if (valueStart == -1 && encodedQueryString.charAt(i) == '=') {
				nameEnd = i;
				valueStart = i + 1;
			}
		}
		if (valueStart == -1)
			nameEnd = encodedQueryString.length();
		decodeQueryParameter(nameStart, nameEnd, valueStart, encodedQueryString.length());
	}
	
	private void decodeQueryParameter(int nameStart, int nameEnd, int valueStart, int valueEnd) {
		int nameLen = nameEnd - nameStart;
		int valueLen = valueStart == -1 ? 0 : valueEnd - valueStart;
		if (nameLen == 0 && valueLen == 0)
			return;
		String name = URLEncoding.decode(encodedQueryString.substring(nameStart, nameStart + nameLen).asReadableBytes()).asString();
		String value = valueLen > 0
			? URLEncoding.decode(encodedQueryString.substring(valueStart, valueStart + valueLen).asReadableBytes()).asString() : "";
		queryParameters.put(name, value);
	}
	
	/** Set a query parameter. */
	public HTTPRequest setQueryParameter(String name, String value) {
		if (queryParameters == null) {
			if (encodedQueryString != null) {
				decodeQueryParameters();
				encodedQueryString = null;
			} else {
				queryParameters = new HashMap<>();
			}
		}
		queryParameters.put(name, value);
		return this;
	}
	
	/** Set the query string in the path. */
	public HTTPRequest setEncodedQueryString(ByteArrayStringIso8859Buffer query) {
		queryParameters = null;
		encodedQueryString = query;
		return this;
	}
	
	/** Set the path and query. */
	public HTTPRequest setEncodedPathAndQuery(ByteArrayStringIso8859Buffer pathAndQuery) {
		int sep = pathAndQuery.indexOf('?');
		if (sep < 0) {
			// only path
			setEncodedPath(pathAndQuery);
			setEncodedQueryString(new ByteArrayStringIso8859Buffer());
			return this;
		}
		setEncodedPath(pathAndQuery.substring(0, sep));
		setEncodedQueryString(pathAndQuery.substring(sep + 1));
		return this;
	}
	
	/** Return the URL encoded query string. */
	public ByteArrayStringIso8859Buffer getEncodedQueryString() {
		if (encodedQueryString == null) {
			if (queryParameters == null) {
				encodedQueryString = new ByteArrayStringIso8859Buffer();
			} else {
				encodeQueryString();
			}
		}
		return encodedQueryString;
	}
	
	private void encodeQueryString() {
		if (queryParameters.isEmpty()) {
			encodedQueryString = new ByteArrayStringIso8859Buffer();
			return;
		}
		encodedQueryString = new ByteArrayStringIso8859Buffer();
		encodedQueryString.setNewArrayStringCapacity(512);
		for (Map.Entry<String, String> param : queryParameters.entrySet()) {
			CharsFromString chars = new CharsFromString(param.getKey());
			URLEncoding.encode(chars, encodedQueryString.asWritableBytes(), false, true);
			encodedQueryString.append((byte)'=');
			chars = new CharsFromString(param.getValue());
			URLEncoding.encode(chars, encodedQueryString.asWritableBytes(), false, true);
		}
	}
	
	/** Set the path and query string from the given URI. */
	public HTTPRequest setURI(URI uri) {
		setDecodedPath(uri.getPath());
		String q = uri.getRawQuery();
		setEncodedQueryString(new ByteArrayStringIso8859Buffer(
			new ByteArrayStringIso8859(q == null ? new byte[0] : uri.getRawQuery().getBytes(StandardCharsets.US_ASCII))));
		return this;
	}
	
	/** Set the path and query string from the given URI. */
	public HTTPRequest setURI(CharSequence uri) {
		if (uri instanceof ByteArrayStringIso8859)
			uri = new ByteArrayStringIso8859Buffer((ByteArrayStringIso8859)uri);
		else if (!(uri instanceof ByteArrayStringIso8859Buffer))
			uri = new ByteArrayStringIso8859Buffer(uri);
		return setEncodedPathAndQuery((ByteArrayStringIso8859Buffer)uri);
	}
	
	/** Return true if this is a valid character for a method. */
	public static boolean isValidMethodChar(byte b) {
		if (b < 0x21) return false;
		if (b > 0x5A) {
			if (b < 0x5E) return false;
			if (b < 0x7B) return true;
			return b == 0x7C || b == 0x7E;
		}
		if (b >= 0x41) return true;
		if (b == 0x40) return false;
		if (b >= 0x30) return true;
		if (b >= 0x2A)
			return b != 0x2F && b != 0x2C;
		return b != 0x22;
	}
	
	/** Return true if this is a valid character for an URI. */
	public static boolean isValidURIChar(byte b) {
		if (b < 0x5B) {
			if (b >= 0x3F) return true;
			if (b >= 0x24)
				return b <= 0x3B || b == 0x3D;
			return b == 0x21;
		}
		if (b >= 0x61)
			return b <= 0x7A || b == 0x7E;
		return b == 0x5F;
	}

	@Override
	public String toString() {
		CharArrayStringBuffer s = new CharArrayStringBuffer();
		s.setNewArrayStringCapacity(512);
		HTTP1RequestCommandProducer.generate(this, s);
		MimeHeaders headers = getHeaders();
		if (headers != null) {
			s.append("\r\n");
			headers.appendTo(s);
		}
		return s.asString();
	}
	
	/** Return true if a body is expected to be sent. */
	public boolean isExpectingBody() {
		if (!methodMayContainBody(method)) return false;
		MimeHeaders headers = getHeaders();
		if (headers == null)
			return false;
		Long size = headers.getContentLength();
		if (size != null)
			return size.longValue() != 0;
		return true;
	}
	
	/** Return true is the given method may contain a body. GET method returns false. */
	public static boolean methodMayContainBody(String method) {
		return !METHOD_GET.equals(method) && !METHOD_CONNECT.equals(method) && !METHOD_HEAD.equals(method);
	}
	
	/** Return true if the connection should be persistent. */
	public boolean isConnectionPersistent() {
		if (getProtocolVersion() != null && getProtocolVersion().getMajor() == 1 && getProtocolVersion().getMinor() == 1) {
			String s = getHeaders().getFirstRawValue(HTTPConstants.Headers.CONNECTION);
			return s == null || !s.equalsIgnoreCase("close");
		}
		return false;
	}
	
	// Convenient methods
	
	/** Return the requested cookie or null. */
	public String getCookie(String name) {
		try {
			for (ParameterizedHeaderValue h : getHeaders().getValues("cookie", ParameterizedHeaderValue.class)) {
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
			for (ParameterizedHeaderValue h : getHeaders().getValues("cookie", ParameterizedHeaderValue.class)) {
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
