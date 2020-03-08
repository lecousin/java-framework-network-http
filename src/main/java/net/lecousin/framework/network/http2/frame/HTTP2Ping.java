package net.lecousin.framework.network.http2.frame;

import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.http2.HTTP2Error;

/** HTTP/2 ping frame. */
public abstract class HTTP2Ping implements HTTP2Frame {

	public static final byte FLAG_ACK	= 0x01;
	
	public static final int OPAQUE_DATA_LENGTH = 8;
	
	protected byte[] opaqueData;
	
	@Override
	public byte getType() {
		return HTTP2FrameHeader.TYPE_PING;
	}
	
	public byte[] getOpaqueData() {
		return opaqueData;
	}
	
	/** HTTP/2 ping frame reader. */
	public static class Reader extends HTTP2Ping implements HTTP2Frame.Reader {
		
		@Override
		public Consumer createConsumer() {
			opaqueData = new byte[OPAQUE_DATA_LENGTH];
			return new Consumer() {
				private int pos = 0;
				
				@Override
				public AsyncSupplier<Boolean, HTTP2Error> consume(ByteBuffer data) {
					int len = Math.min(OPAQUE_DATA_LENGTH - pos, data.remaining());
					data.get(opaqueData, pos, len);
					pos += len;
					return new AsyncSupplier<>(Boolean.valueOf(pos == OPAQUE_DATA_LENGTH), null);
				}
			};
		}
		
	}
	
	/** HTTP/2 ping frame writer. */
	public static class Writer extends HTTP2Ping implements HTTP2Frame.Writer {

		private boolean ack;
		private boolean produced = false;
		
		/** Constructor. */
		public Writer(byte[] opaqueData, boolean ack) {
			this.opaqueData = opaqueData;
			this.ack = ack;
		}
		
		@Override
		public int getStreamId() {
			return 0;
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
			byte[] b = cache.get(HTTP2FrameHeader.LENGTH + OPAQUE_DATA_LENGTH, true);
			HTTP2FrameHeader.write(b, 0, OPAQUE_DATA_LENGTH, HTTP2FrameHeader.TYPE_PING, ack ? FLAG_ACK : 0, 0);
			System.arraycopy(opaqueData, 0, b, HTTP2FrameHeader.LENGTH, OPAQUE_DATA_LENGTH);
			produced = true;
			return new ByteArray(b, 0, HTTP2FrameHeader.LENGTH + OPAQUE_DATA_LENGTH);
		}
		
	}
	
}
