package net.lecousin.framework.network.http1;

import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.text.IString;

/** Produce HTTP/1 response status line. */
public final class HTTP1ResponseStatusProducer {
	
	private HTTP1ResponseStatusProducer() {
		/* no instance. */
	}

	/** Generate the status line with protocol version, status, and message, without ending CRLF. */
	public static void generate(HTTPResponse response, IString s) {
		response.getProtocolVersion().toString(s);
		int statusCode = response.getStatusCode();
		s.append(' ')
		.append((char)('0' + (statusCode / 100)))
		.append((char)('0' + ((statusCode % 100) / 10)))
		.append((char)('0' + (statusCode % 10)))
		.append(' ')
		.append(response.getStatusMessage());
	}

}
