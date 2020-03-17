package net.lecousin.framework.network.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.lecousin.framework.network.AbstractAttributesContainer;
import net.lecousin.framework.network.mime.entity.MimeEntity;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.MimeHeadersContainer;

/** Abstract class for HTTPRequest and HTTPResponse.
 * @param <ME> type of implementation
 */
public abstract class HTTPMessage<ME extends HTTPMessage<ME>> extends AbstractAttributesContainer implements MimeHeadersContainer<ME> {

	protected HTTPProtocolVersion protocolVersion = null;
	protected MimeHeaders headers;
	protected MimeEntity entity;
	protected Map<String, Supplier<String>> trailerHeaderSuppliers = null;
	
	/** Constructor. */
	public HTTPMessage() {
		// nothing
	}
	
	/** Constructor copying the given message. */
	public HTTPMessage(HTTPMessage<ME> copy) {
		protocolVersion = copy.getProtocolVersion();
		entity = copy.getEntity();
		if (entity == null)
			headers = new MimeHeaders(copy.getHeaders().getHeaders());
	}

	/** Set the protocol version. */
	@SuppressWarnings("unchecked")
	public ME setProtocolVersion(HTTPProtocolVersion protocolVersion) {
		this.protocolVersion = protocolVersion;
		return (ME)this;
	}
	
	public HTTPProtocolVersion getProtocolVersion() {
		return protocolVersion;
	}

	/** Return the MIME headers. */
	@Override
	public MimeHeaders getHeaders() {
		if (entity != null)
			return entity.getHeaders();
		if (headers == null) headers = new MimeHeaders();
		return headers;
	}
	
	/** Set the MIME headers. */
	@SuppressWarnings("unchecked")
	public ME setHeaders(MimeHeaders headers) {
		if (entity != null)
			throw new IllegalStateException("Headers cannot be set if an entity is already set");
		this.headers = headers;
		return (ME)this;
	}
	
	public MimeEntity getEntity() {
		return entity;
	}
	
	/** Set the MIME entity. */
	@SuppressWarnings("unchecked")
	public ME setEntity(MimeEntity entity) {
		this.entity = entity;
		this.headers = null;
		return (ME)this;
	}

	/** Add a trailer MIME header. */
	public void addTrailerHeader(String headerName, Supplier<String> supplier) {
		if (trailerHeaderSuppliers == null)
			trailerHeaderSuppliers = new HashMap<>(5);
		trailerHeaderSuppliers.put(headerName, supplier);
	}
	
	/** Get the trailer header supplier, and add their declaration in the headers. */
	public Supplier<List<MimeHeader>> getTrailerHeadersSuppliers() {
		if (trailerHeaderSuppliers == null)
			return null;
		StringBuilder s = new StringBuilder();
		for (String h : trailerHeaderSuppliers.keySet()) {
			if (s.length() > 0)
				s.append(", ");
			s.append(h);
		}
		setHeader("Trailer", s.toString());
		return () -> {
			List<MimeHeader> list = new ArrayList<>(trailerHeaderSuppliers.size());
			for (Map.Entry<String, Supplier<String>> entry : trailerHeaderSuppliers.entrySet()) {
				list.add(new MimeHeader(entry.getKey(), entry.getValue().get()));
			}
			return list;
		};
	}
	
}
