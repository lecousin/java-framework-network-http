package net.lecousin.framework.network.http2.frame;

import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.http2.HTTP2Error;

/** HTTP/2 Frame. */
public interface HTTP2Frame {
	
	/** Return the type of frame. */
	byte getType();

	/** HTTP/2 Frame Writer. */
	interface Writer extends HTTP2Frame {
	
		/** Return the stream id this frame is going to be sent. */
		int getStreamId();
		
		/** Return true if this writer can produce at least one more frame. */
		boolean canProduceMore();
		
		/** Return true if this writer can produce several frames, false if always a single frame. */
		boolean canProduceSeveralFrames();
		
		/** Produce the frame. */
		ByteArray produce(int maxFrameSize, ByteArrayCache cache);
		
	}
	
	/** HTTP/2 Frame Reader. */
	interface Reader extends HTTP2Frame {
		
		/** Create a consumer. */
		Consumer createConsumer();
		
		/** Consumer of bytes to read HTTP/2 frame. */
		interface Consumer {
			
			/** Consume data, return true if the frame has been fully read, false if more bytes are expected.
			 * This is a partial consumer, so data is not freed.
			 */
			AsyncSupplier<Boolean, HTTP2Error> consume(ByteBuffer data);
			
		}
		
		static class SkipConsumer implements Consumer {
			private int remaining;
			
			public SkipConsumer(int size) {
				remaining = size;
			}
			
			@Override
			public AsyncSupplier<Boolean, HTTP2Error> consume(ByteBuffer data) {
				int len = Math.min(remaining, data.remaining());
				data.position(data.position() + len);
				remaining -= len;
				return new AsyncSupplier<>(Boolean.valueOf(remaining == 0), null);
			}
		}
	}
	
	/** Empty payload frame writer. */
	public static class EmptyWriter implements HTTP2Frame.Writer {
		
		private HTTP2FrameHeader header;
		private boolean produced = false;
		
		/** Constructor. */
		public EmptyWriter(HTTP2FrameHeader header) {
			this.header = header;
		}
		
		@Override
		public byte getType() {
			return header.getType();
		}
		
		@Override
		public int getStreamId() {
			return header.getStreamId();
		}
		
		@Override
		public boolean canProduceMore() {
			return !produced;
		}
		
		@Override
		public boolean canProduceSeveralFrames() {
			return false;
		}
		
		@Override
		public ByteArray produce(int maxFrameSize, ByteArrayCache cache) {
			byte[] b = cache.get(HTTP2FrameHeader.LENGTH, true);
			header.write(b, 0);
			produced = true;
			return new ByteArray(b, 0, HTTP2FrameHeader.LENGTH);
		}
		
	}
	
}
