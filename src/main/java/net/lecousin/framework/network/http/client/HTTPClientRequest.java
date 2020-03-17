package net.lecousin.framework.network.http.client;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;

import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.exception.UnsupportedHTTPProtocolException;
import net.lecousin.framework.network.mime.entity.MimeEntity;

/** HTTP request to be sent. */
public class HTTPClientRequest extends HTTPRequest {
	
	/** Constructor. */
	public HTTPClientRequest(String hostname, int port, boolean isSecure, HTTPRequest copy) {
		super(copy);
		this.hostname = hostname;
		if (port <= 0)
			this.port = isSecure ? HTTPConstants.DEFAULT_HTTPS_PORT : HTTPConstants.DEFAULT_HTTP_PORT;
		else
			this.port = port;
		this.secure = isSecure;
	}
	
	/** Constructor. */
	public HTTPClientRequest(String hostname, int port, boolean isSecure) {
		this.hostname = hostname;
		if (port <= 0)
			this.port = isSecure ? HTTPConstants.DEFAULT_HTTPS_PORT : HTTPConstants.DEFAULT_HTTP_PORT;
		else
			this.port = port;
		this.secure = isSecure;
	}
	
	/** Constructor. */
	public HTTPClientRequest(String hostname, boolean isSecure) {
		this(hostname, 0, isSecure);
	}
	
	/** Constructor. */
	public HTTPClientRequest(InetSocketAddress address, boolean isSecure) {
		this(address.getHostString(), address.getPort(), isSecure);
	}
	
	/** Constructor. */
	public HTTPClientRequest(URI uri) throws UnsupportedHTTPProtocolException {
		this(uri.getHost(), uri.getPort(), checkScheme(uri.getScheme()));
		super.setURI(uri);
	}
	
	private static boolean checkScheme(String scheme) throws UnsupportedHTTPProtocolException {
		if (scheme == null) throw new UnsupportedHTTPProtocolException(null);
		scheme = scheme.toLowerCase();
		if (!HTTPConstants.HTTP_SCHEME.equals(scheme) && !HTTPConstants.HTTPS_SCHEME.equals(scheme))
			throw new UnsupportedHTTPProtocolException(scheme);
		return HTTPConstants.HTTPS_SCHEME.equals(scheme);
	}

	private String hostname;
	private int port;
	private boolean secure;
	
	public String getHostname() {
		return hostname;
	}
	
	public void setHostname(String hostname) {
		this.hostname = hostname;
	}
	
	public int getPort() {
		return port;
	}
	
	public void setPort(int port) {
		this.port = port;
	}
	
	public boolean isSecure() {
		return secure;
	}
	
	public void setSecure(boolean secure) {
		this.secure = secure;
	}
	
	/** Generate the URI to which this request has to be sent. */
	public URI generateURI() throws URISyntaxException {
		return new URI(secure ? HTTPConstants.HTTPS_SCHEME : HTTPConstants.HTTP_SCHEME, null, hostname, port,
			getEncodedPath().asString(), getEncodedQueryString().asString(), null);
	}
	
	@Override
	public HTTPClientRequest setURI(URI uri) {
		if (uri.getHost() != null) {
			setHostname(uri.getHost());
			if (uri.getScheme() != null) {
				secure = HTTPConstants.HTTPS_SCHEME.equalsIgnoreCase(uri.getScheme());
			}
			if (uri.getPort() > 0)
				setPort(uri.getPort());
			else
				setPort(secure ? HTTPConstants.DEFAULT_HTTPS_PORT : HTTPConstants.DEFAULT_HTTP_PORT);
		}
		return (HTTPClientRequest)super.setURI(uri);
	}
	
	/** Set method GET. */
	public HTTPClientRequest get() {
		return (HTTPClientRequest)setMethod(METHOD_GET);
	}
	
	/** Set method GET with given path and query string. */
	public HTTPClientRequest get(CharSequence uri) {
		return (HTTPClientRequest)setMethod(METHOD_GET).setURI(uri);
	}
	
	/** Set method POST. */
	public HTTPClientRequest post() {
		return (HTTPClientRequest)setMethod(METHOD_POST);
	}

	/** Set method POST with given entity. */
	public HTTPClientRequest post(MimeEntity entity) {
		return (HTTPClientRequest)setMethod(METHOD_POST).setEntity(entity);
	}

	/** Set method POST with given path and query string. */
	public HTTPClientRequest post(CharSequence uri) {
		return (HTTPClientRequest)setMethod(METHOD_POST).setURI(uri);
	}
	
	/** Set method POST with given path and query string and entity. */
	public HTTPClientRequest post(CharSequence uri, MimeEntity entity) {
		return (HTTPClientRequest)setMethod(METHOD_POST).setURI(uri).setEntity(entity);
	}
	
	/** Set method PUT. */
	public HTTPClientRequest put() {
		return (HTTPClientRequest)setMethod(METHOD_PUT);
	}
	
	/** Set method PUT with given entity. */
	public HTTPClientRequest put(MimeEntity entity) {
		return (HTTPClientRequest)setMethod(METHOD_PUT).setEntity(entity);
	}

	/** Set method PUT with given path and query string. */
	public HTTPClientRequest put(CharSequence uri) {
		return (HTTPClientRequest)setMethod(METHOD_PUT).setURI(uri);
	}
	
	/** Set method PUT with given path and query string and entity. */
	public HTTPClientRequest put(CharSequence uri, MimeEntity entity) {
		return (HTTPClientRequest)setMethod(METHOD_PUT).setURI(uri).setEntity(entity);
	}
	
	/** Set method DELETE. */
	public HTTPClientRequest delete() {
		return (HTTPClientRequest)setMethod(METHOD_DELETE);
	}

	/** Set method DELETE with given path and query string. */
	public HTTPClientRequest delete(CharSequence uri) {
		return (HTTPClientRequest)setMethod(METHOD_DELETE).setURI(uri);
	}
	
	@Override
	public HTTPClientRequest setEntity(MimeEntity entity) {
		return (HTTPClientRequest)super.setEntity(entity);
	}

	// TODO proxy
	// TODO dependencies and priorities
	
	
	
}
