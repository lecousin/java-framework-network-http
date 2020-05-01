package net.lecousin.framework.network.http1;

import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.text.ByteArrayStringIso8859Buffer;
import net.lecousin.framework.text.CharArrayStringBuffer;
import net.lecousin.framework.text.IString;

/** Produce HTTP/1 request command line. */
public final class HTTP1RequestCommandProducer {
	
	private HTTP1RequestCommandProducer() {
		/* no instance. */
	}

	/** Generate the command line. */
	public static void generate(HTTPRequest request, CharSequence pathPrefix, IString s) {
		if (request.getMethod() == null)
			s.append("NULL ");
		else
			s.append(request.getMethod()).append(' ');
		if (pathPrefix != null)
			s.append(pathPrefix);
		ByteArrayStringIso8859Buffer path = request.getEncodedPath();
		if (path == null)
			s.append('/');
		else
			s.append(path.asString());
		ByteArrayStringIso8859Buffer query = request.getEncodedQueryString();
		if (!query.isEmpty()) {
			s.append('?');
			s.append(query.asString());
		}
		s.append(' ');
		if (request.getProtocolVersion() == null)
			s.append("HTTP/1.1");
		else
			request.getProtocolVersion().toString(s);
	}
	
	/** Generate the command line. */
	public static String generateString(HTTPRequest request) {
		CharArrayStringBuffer line = new CharArrayStringBuffer();
		line.setNewArrayStringCapacity(256);
		generate(request, null, line);
		return line.asString();
	}

}
