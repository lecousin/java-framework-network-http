package net.lecousin.framework.network.http2.frame;

import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.HTTP2PseudoHeaderHandler;
import net.lecousin.framework.network.http2.hpack.HPackDecompress;
import net.lecousin.framework.network.mime.header.MimeHeaders;

/** HTTP/2 CONTINUATION frame. */
public abstract class HTTP2Continuation implements HTTP2Frame {

	public static final byte FLAG_END_HEADERS	= 0x04;
	
	@Override
	public byte getType() {
		return HTTP2FrameHeader.TYPE_CONTINUATION;
	}
	
	/** HTTP/2 CONTINUATION frame reader. */
	public static class Reader extends HTTP2Continuation implements HTTP2Frame.Reader {

		private HTTP2FrameHeader header;
		private HPackDecompress decompressionContext;
		private MimeHeaders mimeHeaders;
		private HTTP2PseudoHeaderHandler pseudoHeaderHandler;

		/** Constructor. */
		public Reader(
			HTTP2FrameHeader header, HPackDecompress decompressionContext,
			MimeHeaders mimeHeaders, HTTP2PseudoHeaderHandler pseudoHeaderHandler
		) {
			this.header = header;
			this.decompressionContext = decompressionContext;
			this.mimeHeaders = mimeHeaders;
			this.pseudoHeaderHandler = pseudoHeaderHandler;
		}
		
		@Override
		public HTTP2Frame.Reader.Consumer createConsumer() {
			return new Consumer();
		}
	
		/** Consumer of CONTINUATION frame payload. */
		public class Consumer implements HTTP2Frame.Reader.Consumer {
			
			private int pos = 0;
			
			@Override
			public AsyncSupplier<Boolean, HTTP2Error> consume(ByteBuffer data) {
				ByteBuffer b;
				int len = data.remaining();
				if (len <= header.getPayloadLength() - pos) {
					b = data;
				} else {
					len = header.getPayloadLength() - pos;
					b = data.duplicate();
					b.limit(b.position() + len);
				}

				try {
					decompressionContext.consume(b, mimeHeaders, pseudoHeaderHandler);
				} catch (HTTP2Error e) {
					return new AsyncSupplier<>(null, e);
				}

				pos += len;
				if (pos < header.getPayloadLength())
					return new AsyncSupplier<>(Boolean.FALSE, null);
				decompressionContext = null; // not used anymore
				mimeHeaders = null; // not used anymore
				return new AsyncSupplier<>(Boolean.TRUE, null);
			}
		}
		
	}

}
