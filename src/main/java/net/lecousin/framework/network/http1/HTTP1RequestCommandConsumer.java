package net.lecousin.framework.network.http1;

import java.net.HttpURLConnection;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.util.PartialAsyncConsumer;
import net.lecousin.framework.io.data.Bytes;
import net.lecousin.framework.network.http.HTTPProtocolVersion;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.exception.HTTPError;
import net.lecousin.framework.network.mime.MimeUtil;
import net.lecousin.framework.text.ByteArrayStringIso8859;
import net.lecousin.framework.text.ByteArrayStringIso8859Buffer;

/** HTTP/1 request command line consumer and parser. */
public class HTTP1RequestCommandConsumer extends PartialAsyncConsumer.ConsumerQueue<Bytes.Readable, HTTPError> {

	private HTTPRequest request;
	
	/** Constructor. */
	public HTTP1RequestCommandConsumer(HTTPRequest request) {
		super();
		queue.add(new MethodConsumer());
		queue.add(new URIConsumer());
		queue.add(new ProtocolVersionConsumer());
		this.request = request;
		nextConsumer();
	}
	
	private class MethodConsumer implements PartialAsyncConsumer<Bytes.Readable, HTTPError> {
		
		private ByteArrayStringIso8859 str = new ByteArrayStringIso8859(8);
		
		@Override
		public AsyncSupplier<Boolean, HTTPError> consume(Bytes.Readable data) {
			while (data.hasRemaining()) {
				byte b = data.get();
				if (b == ' ') {
					request.setMethod(str.asString());
					str = null;
					return new AsyncSupplier<>(Boolean.TRUE, null);
				}
				if (!MimeUtil.isValidTokenCharacter(b))
					return new AsyncSupplier<>(null, new HTTPError(HttpURLConnection.HTTP_BAD_REQUEST,
						"Invalid method character: " + (b & 0xFF)));
				if (str.length() >= HTTPRequest.MAX_METHOD_LENGTH)
					return new AsyncSupplier<>(null, new HTTPError(HttpURLConnection.HTTP_BAD_REQUEST, "Method too long"));
				str.append(b);
			}
			return new AsyncSupplier<>(Boolean.FALSE, null);
		}
		
		@Override
		public boolean isExpectingData() {
			return str != null;
		}
		
	}

	private class URIConsumer implements PartialAsyncConsumer<Bytes.Readable, HTTPError> {

		private ByteArrayStringIso8859Buffer str = new ByteArrayStringIso8859Buffer(new ByteArrayStringIso8859(256));
		private int length = 0;
		
		private URIConsumer() {
			str.setNewArrayStringCapacity(512);
		}
		
		@Override
		public AsyncSupplier<Boolean, HTTPError> consume(Bytes.Readable data) {
			while (data.hasRemaining()) {
				byte b = data.get();
				if (b == ' ') {
					request.setURI(str);
					str = null;
					return new AsyncSupplier<>(Boolean.TRUE, null);
				}
				if (length++ >= HTTPRequest.MAX_URI_LENGTH)
					return new AsyncSupplier<>(null, new HTTPError(HttpURLConnection.HTTP_REQ_TOO_LONG, "Request URI too long"));
				if (!HTTPRequest.isValidURIChar(b))
					return new AsyncSupplier<>(null, new HTTPError(HttpURLConnection.HTTP_BAD_REQUEST,
						"Invalid URI: character " + (int)b + " found"));
				str.append(b);
			}
			return new AsyncSupplier<>(Boolean.FALSE, null);
		}
		
		@Override
		public boolean isExpectingData() {
			return str != null;
		}
		
	}

	private static final HTTPError PROTOCOL_VERSION_ERROR = new HTTPError(HttpURLConnection.HTTP_BAD_REQUEST, "Invalid protocol version");
	private static final byte[] PROTOCOL_VERSION_EXPECTED = new byte[] { 'H', 'T', 'T', 'P', '/', 'x', '.', 'y', '\r' };

	private class ProtocolVersionConsumer implements PartialAsyncConsumer<Bytes.Readable, HTTPError> {
		
		private int pos = 0;
		private byte major;
		private byte minor;
		
		@Override
		public AsyncSupplier<Boolean, HTTPError> consume(Bytes.Readable data) {
			while (data.hasRemaining()) {
				byte b = data.get();
				switch (pos) {
				case 5:
					major = (byte)(b - '0');
					if (major < 0 || major > 9) return new AsyncSupplier<>(null, PROTOCOL_VERSION_ERROR);
					break;
				case 7:
					minor = (byte)(b - '0');
					if (minor < 0 || minor > 9) return new AsyncSupplier<>(null, PROTOCOL_VERSION_ERROR);
					break;
				case 9:
					if (b != '\n') return new AsyncSupplier<>(null, PROTOCOL_VERSION_ERROR);
					request.setProtocolVersion(new HTTPProtocolVersion(major, minor));
					return new AsyncSupplier<>(Boolean.TRUE, null);
				default:
					if (b != PROTOCOL_VERSION_EXPECTED[pos]) return new AsyncSupplier<>(null, PROTOCOL_VERSION_ERROR);
					break;
				}
				pos++;
			}
			return new AsyncSupplier<>(Boolean.FALSE, null);
		}
		
		@Override
		public boolean isExpectingData() {
			return pos < 10;
		}
		
	}
	
}
