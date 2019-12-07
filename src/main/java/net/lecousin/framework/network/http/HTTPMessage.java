package net.lecousin.framework.network.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.lecousin.framework.network.AbstractAttributesContainer;
import net.lecousin.framework.network.mime.MimeHeader;
import net.lecousin.framework.network.mime.MimeMessage;

/** Abstract class for HTTPRequest and HTTPResponse. */
public abstract class HTTPMessage extends AbstractAttributesContainer {

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

	private Protocol protocol = null;
	private MimeMessage mime = new MimeMessage();
	private Map<String, Supplier<String>> trailerHeaderSuppliers = null;

	public void setProtocol(Protocol protocol) {
		this.protocol = protocol;
	}
	
	public Protocol getProtocol() {
		return protocol;
	}

	public MimeMessage getMIME() {
		return mime;
	}

	public void setMIME(MimeMessage mime) {
		this.mime = mime;
	}

	/** Set a header. */
	public void setHeaderRaw(String name, String value) {
		mime.setHeaderRaw(name, value);
	}
	
	/** Add a value to a header. */
	public void addHeaderRaw(String headerName, String value) {
		mime.addHeaderRaw(headerName, value);
	}

	/** Add a trailer MIME header. */
	public void addTrailerHeader(String headerName, Supplier<String> supplier) {
		if (trailerHeaderSuppliers == null)
			trailerHeaderSuppliers = new HashMap<>(5);
		trailerHeaderSuppliers.put(headerName, supplier);
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
	
}
