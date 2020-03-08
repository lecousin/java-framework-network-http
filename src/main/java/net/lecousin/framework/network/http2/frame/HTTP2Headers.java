package net.lecousin.framework.network.http2.frame;

import java.nio.ByteBuffer;
import java.util.List;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.HTTP2PseudoHeaderHandler;
import net.lecousin.framework.network.http2.hpack.HPackCompress;
import net.lecousin.framework.network.http2.hpack.HPackDecompress;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.Runnables.FunctionThrows;

/** Headers frame. */
public abstract class HTTP2Headers extends HTTP2Priority {
	
	public static final byte FLAG_END_STREAM	= 0x01;
	public static final byte FLAG_END_HEADERS	= 0x04;
	public static final byte FLAG_PADDED		= 0x08;
	public static final byte FLAG_PRIORITY		= 0x20;
	
	protected int padLength;
	
	@Override
	public byte getType() {
		return HTTP2FrameHeader.TYPE_HEADERS;
	}
	
	/** HTTP/2 HEADERS frame reader. */
	public static class Reader extends HTTP2Headers implements HTTP2Frame.Reader {

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
		public boolean hasPriority() {
			return header.hasFlags(FLAG_PRIORITY);
		}
		
		@Override
		public HTTP2Frame.Reader.Consumer createConsumer() {
			return new Consumer();
		}
	
		/** Consumer of HEADERS frame payload. */
		public class Consumer implements HTTP2Frame.Reader.Consumer {
			
			private FunctionThrows<ByteBuffer, Boolean, HTTP2Error> currentConsumer;
			
			/** Constructor. */
			public Consumer() {
				if (header.hasFlags(FLAG_PADDED))
					currentConsumer = new PadLengthConsumer();
				else if (header.hasFlags(FLAG_PRIORITY))
					currentConsumer = new PriorityConsumer();
				else
					currentConsumer = new HeaderBlockConsumer(header.getPayloadLength());
			}
			
			@Override
			@SuppressWarnings("java:S2589") // currentConsumer is modified
			public AsyncSupplier<Boolean, HTTP2Error> consume(ByteBuffer data) {
				try {
					do {
						if (!currentConsumer.apply(data).booleanValue())
							return new AsyncSupplier<>(Boolean.FALSE, null);
					} while (currentConsumer != null);
					return new AsyncSupplier<>(Boolean.TRUE, null);
				} catch (HTTP2Error e) {
					return new AsyncSupplier<>(null, e);
				}
			}
			
			private class PadLengthConsumer implements FunctionThrows<ByteBuffer, Boolean, HTTP2Error> {
				@Override
				public Boolean apply(ByteBuffer data) throws HTTP2Error {
					if (!data.hasRemaining())
						return Boolean.FALSE;
					padLength = data.get() & 0xFF;
					int minimumLength = 1 + padLength;
					if (header.hasFlags(FLAG_PRIORITY)) {
						currentConsumer = new PriorityConsumer();
						minimumLength += 5;
					} else {
						currentConsumer = new HeaderBlockConsumer(header.getPayloadLength() - 1 - padLength);
					}
					if (minimumLength > header.getPayloadLength())
						throw new HTTP2Error(false, HTTP2Error.Codes.PROTOCOL_ERROR);
					return Boolean.TRUE;
				}
			}
			
			private class PriorityConsumer implements FunctionThrows<ByteBuffer, Boolean, HTTP2Error> {
				private int pos = 0;
				
				@Override
				public Boolean apply(ByteBuffer data) {
					while (data.hasRemaining()) {
						byte b = data.get();
						if (pos == 0) {
							exclusiveDependency = (b & 0x80) == 0x80;
							streamDependency = (b & 0x7F) << 24;
							pos++;
						} else if (pos == 4) {
							dependencyWeight = b & 0xFF;
							int blockSize = header.getPayloadLength() - 5;
							if (header.hasFlags(FLAG_PADDED))
								blockSize -= 1 + padLength;
							currentConsumer = new HeaderBlockConsumer(blockSize);
							return Boolean.TRUE;
						} else {
							streamDependency = (b & 0xFF) << (8 * (3 - pos));
							pos++;
						}
					}
					return Boolean.FALSE;
				}
			}
			
			private class HeaderBlockConsumer implements FunctionThrows<ByteBuffer, Boolean, HTTP2Error> {
				private int size;
				private int pos = 0;
				
				private HeaderBlockConsumer(int size) {
					this.size = size;
				}
				
				@Override
				public Boolean apply(ByteBuffer data) throws HTTP2Error {
					ByteBuffer b;
					int len = data.remaining();
					if (len <= header.getPayloadLength() - pos) {
						b = data;
					} else {
						len = header.getPayloadLength() - pos;
						b = data.duplicate();
						b.limit(b.position() + len);
					}

					decompressionContext.consume(b, mimeHeaders, pseudoHeaderHandler);

					pos += len;
					if (pos < size)
						return Boolean.FALSE;
					decompressionContext = null; // not used anymore
					mimeHeaders = null; // not used anymore
					if (header.hasFlags(FLAG_PADDED))
						currentConsumer = new PaddingConsumer();
					else
						currentConsumer = null;
					return Boolean.TRUE;
				}
			}
			
			private class PaddingConsumer implements FunctionThrows<ByteBuffer, Boolean, HTTP2Error> {
				private int pos = 0;
				
				@Override
				public Boolean apply(ByteBuffer data) throws HTTP2Error {
					int len = Math.min(data.remaining(), padLength - pos);
					data.position(data.position() + len);
					pos += len;
					if (pos < padLength)
						return Boolean.FALSE;
					currentConsumer = null;
					return Boolean.TRUE;
				}
			}
			
		}
	}
	
	/** HTTP/2 headers frame writer. */
	public static class Writer extends HTTP2Headers implements HTTP2Frame.Writer {
		
		private int streamId;
		private List<Pair<String, String>> headers;
		private boolean isEndOfStream;
		private HPackCompress compressor;
		private boolean firstFrame = true;
		private Runnable onFramesProduced;
		
		/** Constructor. */
		public Writer(
			int streamId, List<Pair<String, String>> headers, boolean isEndOfStream,
			HPackCompress compressor, Runnable onFramesProduced
		) {
			this.streamId = streamId;
			this.headers = headers;
			this.isEndOfStream = isEndOfStream;
			this.compressor = compressor;
			this.onFramesProduced = onFramesProduced;
		}
		
		@Override
		public byte getType() {
			return HTTP2FrameHeader.TYPE_HEADERS;
		}
		
		@Override
		public int getStreamId() {
			return streamId;
		}
		
		@Override
		public boolean canProduceMore() {
			return !headers.isEmpty();
		}
		
		@Override
		public boolean canProduceSeveralFrames() {
			return true;
		}

		@Override
		public ByteArray produce(int maxFrameSize, ByteArrayCache cache) {
			byte[] b = cache.get(HTTP2FrameHeader.LENGTH + Math.min(maxFrameSize, 16384), true);
			ByteArray.Writable buffer = new ByteArray.Writable(
				b, HTTP2FrameHeader.LENGTH, Math.min(maxFrameSize, b.length - HTTP2FrameHeader.LENGTH), true);
			while (!headers.isEmpty()) {
				Pair<String, String> header = headers.get(0);
				if (!compressor.compress(header.getValue1(), header.getValue2(), true, true, buffer))
					break;
			}
			byte flags = 0;
			if (isEndOfStream && firstFrame)
				flags |= FLAG_END_STREAM;
			if (headers.isEmpty())
				flags |= FLAG_END_HEADERS;
			HTTP2FrameHeader.write(b, 0, buffer.position() - HTTP2FrameHeader.LENGTH,
				firstFrame ? HTTP2FrameHeader.TYPE_HEADERS : HTTP2FrameHeader.TYPE_CONTINUATION, flags, streamId);
			firstFrame = false;
			if (headers.isEmpty() && onFramesProduced != null)
				onFramesProduced.run();
			return new ByteArray(b, 0, buffer.position());
		}
		
	}
	
}
