package net.lecousin.framework.network.http2.frame;

import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.io.util.DataUtil;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.http2.HTTP2Error;

/** RST_STREAM frame. */
public abstract class HTTP2ResetStream implements HTTP2Frame {
	
	protected int errorCode;
	
	@Override
	public byte getType() {
		return HTTP2FrameHeader.TYPE_RST_STREAM;
	}

	/** HTTP/2 RST_STREAM frame reader. */
	public static class Reader extends HTTP2ResetStream implements HTTP2Frame.Reader {
		
		@Override
		public Consumer createConsumer() {
			errorCode = 0;
			return new Consumer() {
				private int pos = 0;
				
				@Override
				public AsyncSupplier<Boolean, HTTP2Error> consume(ByteBuffer data) {
					while (data.hasRemaining()) {
						errorCode = (errorCode << 8) | (data.get() & 0xFF);
						if (++pos == 4)
							return new AsyncSupplier<>(Boolean.TRUE, null);
					}
					return new AsyncSupplier<>(Boolean.FALSE, null);
				}
			};
		}
		
	}
	
	/** HTTP/2 RST_STREAM frame writer. */
	public static class Writer extends HTTP2ResetStream implements HTTP2Frame.Writer {

		private HTTP2FrameHeader header;
		private boolean produced = false;

		/** Constructor. */
		public Writer(int streamId, int errorCode) {
			this.errorCode = errorCode;
			this.header = new HTTP2FrameHeader(4, HTTP2FrameHeader.TYPE_RST_STREAM, (byte)0, streamId);
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
			int frameLength = HTTP2FrameHeader.LENGTH + 4;
			byte[] buffer = cache.get(frameLength, true);
			header.write(buffer, 0);
			DataUtil.Write32.BE.write(buffer, HTTP2FrameHeader.LENGTH, errorCode);
			produced = true;
			return new ByteArray(buffer, 0, frameLength);
		}
		
	}
	
}
