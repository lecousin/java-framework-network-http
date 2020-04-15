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
	void emptyEntityReceived(StreamsManager manager, DataStreamHandler stream);

	/** Return the body consumer. */
	AsyncConsumer<ByteBuffer, IOException> endOfHeaders(StreamsManager manager, DataStreamHandler stream) throws Exception;
	
	void endOfBody(StreamsManager manager, DataStreamHandler stream);
	
	void endOfTrailers(StreamsManager manager, DataStreamHandler stream);
	
	void close();
	
}
