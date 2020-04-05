package net.lecousin.framework.network.http2.frame;

import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.io.util.DataUtil;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.http2.HTTP2Error;

/** HTTP/2 ping frame. */
public abstract class HTTP2WindowUpdate implements HTTP2Frame {

	public static final int LENGTH = 4;
	
	protected long increment;
	
	@Override
	public byte getType() {
		return HTTP2FrameHeader.TYPE_WINDOW_UPDATE;
	}
	
	public long getIncrement() {
		return increment;
	}
	
	/** HTTP/2 ping frame reader. */
	public static class Reader extends HTTP2WindowUpdate implements HTTP2Frame.Reader {
		
		@Override
		public Consumer createConsumer() {
			increment = 0;
			return new Consumer() {
				private int pos = 0;
				
				@Override
				public AsyncSupplier<Boolean, HTTP2Error> consume(ByteBuffer data) {
					while (data.hasRemaining()) {
						increment = (increment << 8) | (data.get() & 0xFF);
						if (++pos == 4) {
							increment = (increment & 0x7FFFFFFF);
							return new AsyncSupplier<>(Boolean.TRUE, null);
						}
					}
					return new AsyncSupplier<>(Boolean.FALSE, null);
				}
			};
		}
		
	}
	
	/** HTTP/2 ping frame writer. */
	public static class Writer extends HTTP2WindowUpdate implements HTTP2Frame.Writer {

		private int streamId;
		private boolean produced = false;
		
		/** Constructor. */
		public Writer(int streamId, long increment) {
			this.streamId = streamId;
			this.increment = increment;
		}
		
		public void setIncrement(long increment) {
			this.increment = increment;
		}
		
		@Override
		public int getStreamId() {
			return streamId;
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
			byte[] b = cache.get(HTTP2FrameHeader.LENGTH + 4, true);
			HTTP2FrameHeader.write(b, 0, 4, HTTP2FrameHeader.TYPE_WINDOW_UPDATE, (byte)0, streamId);
			DataUtil.Write32U.BE.write(b, HTTP2FrameHeader.LENGTH, increment);
			produced = true;
			return new ByteArray(b, 0, HTTP2FrameHeader.LENGTH + 4);
		}
		
	}
	
}
