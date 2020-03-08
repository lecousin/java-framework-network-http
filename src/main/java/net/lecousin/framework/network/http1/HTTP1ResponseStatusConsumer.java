package net.lecousin.framework.network.http1;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.util.PartialAsyncConsumer;
import net.lecousin.framework.io.data.Bytes;
import net.lecousin.framework.network.http.HTTPProtocolVersion;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.exception.HTTPException;
import net.lecousin.framework.text.ByteArrayStringIso8859;
import net.lecousin.framework.text.ByteArrayStringIso8859Buffer;

/** HTTP/1 response status line consumer and parser. */
public class HTTP1ResponseStatusConsumer extends PartialAsyncConsumer.ConsumerQueue<Bytes.Readable, HTTPException> {

	private HTTPResponse response;
	
	/** Constructor. */
	public HTTP1ResponseStatusConsumer(HTTPResponse response) {
		this.response = response;
		this.queue.add(new ProtocolVersionConsumer());
		this.queue.add(new StatusCodeConsumer());
		this.queue.add(new StatusMessageConsumer());
		nextConsumer();
	}
	
	private static final HTTPException PROTOCOL_VERSION_ERROR = new HTTPException("Invalid protocol version");
	private static final byte[] PROTOCOL_VERSION_EXPECTED = new byte[] { 'H', 'T', 'T', 'P', '/' };

	private class ProtocolVersionConsumer implements PartialAsyncConsumer<Bytes.Readable, HTTPException> {
		
		private int pos = 0;
		private byte major;
		private byte minor;
		
		@Override
		public AsyncSupplier<Boolean, HTTPException> consume(Bytes.Readable data) {
			while (data.hasRemaining()) {
				byte b = data.get();
				switch (pos) {
				case 5:
					major = (byte)(b - '0');
					if (major < 0 || major > 9) return new AsyncSupplier<>(null, PROTOCOL_VERSION_ERROR);
					break;
				case 6:
					if (b != '.') return new AsyncSupplier<>(null, PROTOCOL_VERSION_ERROR);
					break;
				case 7:
					minor = (byte)(b - '0');
					if (minor < 0 || minor > 9) return new AsyncSupplier<>(null, PROTOCOL_VERSION_ERROR);
					break;
				case 8:
					if (b != ' ') return new AsyncSupplier<>(null, PROTOCOL_VERSION_ERROR);
					response.setProtocolVersion(new HTTPProtocolVersion(major, minor));
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
			return pos < 9;
		}
		
	}

	private class StatusCodeConsumer implements PartialAsyncConsumer<Bytes.Readable, HTTPException> {
	
		private int statusCode = 0;
		private int pos = 0;
		
		@Override
		public AsyncSupplier<Boolean, HTTPException> consume(Bytes.Readable data) {
			while (data.hasRemaining()) {
				byte b = data.get();
				b -= '0';
				if (b < 0 || b > 9) return new AsyncSupplier<>(null, new HTTPException("Unexpected character for status code"));
				statusCode = statusCode * 10 + b;
				if (++pos == 3) {
					response.setStatus(statusCode);
					return new AsyncSupplier<>(Boolean.TRUE, null);
				}
			}
			return new AsyncSupplier<>(Boolean.FALSE, null);
		}
		
		@Override
		public boolean isExpectingData() {
			return pos < 3;
		}
	}

	private class StatusMessageConsumer implements PartialAsyncConsumer<Bytes.Readable, HTTPException> {
		
		private boolean firstSpace = true;
		private boolean endOfLine = false;
		private ByteArrayStringIso8859Buffer str = new ByteArrayStringIso8859Buffer(new ByteArrayStringIso8859(256));
		
		@Override
		public AsyncSupplier<Boolean, HTTPException> consume(Bytes.Readable data) {
			while (data.hasRemaining()) {
				byte b = data.get();
				if (firstSpace) {
					if (b != ' ') return new AsyncSupplier<>(null, new HTTPException("Unexpected character after status code"));
					firstSpace = false;
					continue;
				}
				if (b == '\n') {
					endOfLine = true;
					response.setStatusMessage(str.toString());
					return new AsyncSupplier<>(Boolean.TRUE, null);
				}
				if (b == '\r') continue;
				str.append(b);
			}
			return new AsyncSupplier<>(Boolean.FALSE, null);
		}
		
		@Override
		public boolean isExpectingData() {
			return !endOfLine;
		}
	}
	
}
