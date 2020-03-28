package net.lecousin.framework.network.http2.streams;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.network.http2.HTTP2PseudoHeaderHandler;
import net.lecousin.framework.network.mime.header.MimeHeaders;

public interface DataHandler {

	MimeHeaders getReceivedHeaders();
	
	HTTP2PseudoHeaderHandler createPseudoHeaderHandler();
	
	/** Signal an empty entity, but headers may not yet be fully received. */
	void emptyEntityReceived();

	/** Return the body consumer. */
	AsyncConsumer<ByteBuffer, IOException> endOfHeaders() throws Exception;
	
	void endOfBody();
	
	void endOfTrailers();
	
}
