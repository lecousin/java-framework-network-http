package net.lecousin.framework.network.http2.frame;

import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.io.util.DataUtil;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.http2.HTTP2Error;

/** GOAWAY frame. */
public abstract class HTTP2GoAway implements HTTP2Frame {
	
	public static final int MINIMUM_PAYLOAD_LENGTH = 8;
	
	protected int lastStreamID;
	protected int errorCode;
	protected byte[] debugMessage;
	
	@Override
	public byte getType() {
		return HTTP2FrameHeader.TYPE_GOAWAY;
	}
	
	public int getLastStreamID() {
		return lastStreamID;
	}

	public int getErrorCode() {
		return errorCode;
	}

	public byte[] getDebugMessage() {
		return debugMessage;
	}



	/** HTTP/2 GOAWAY frame reader. */
	public static class Reader extends HTTP2GoAway implements HTTP2Frame.Reader {
		
		public Reader(HTTP2FrameHeader header) {
			this.header = header;
		}
		
		private HTTP2FrameHeader header;
		
		@Override
		public Consumer createConsumer() {
			return new Consumer() {
				
				private int pos = 0;
				
				@Override
				public AsyncSupplier<Boolean, HTTP2Error> consume(ByteBuffer data) {
					while (data.hasRemaining()) {
						if (pos == 0) {
							lastStreamID = (data.get() & 0xFF) << 24;
						} else if (pos < 4) {
							lastStreamID = (data.get() & 0xFF) << (8 * (3 - pos));
						} else if (pos == 4) {
							errorCode = (data.get() & 0xFF) << 24;
						} else if (pos < 8) {
							errorCode = (data.get() & 0xFF) << (8 * (7 - pos));
						} else {
							if (pos == header.getPayloadLength())
								return new AsyncSupplier<>(Boolean.TRUE, null);
							if (debugMessage == null)
								debugMessage = new byte[header.getPayloadLength() - 8];
							debugMessage[pos - 8] = data.get();
						}
						pos++;
					}
					return new AsyncSupplier<>(Boolean.valueOf(pos == header.getPayloadLength()), null);
				}
			};
		}
		
	}
	
	/** HTTP/2 GOAWAY frame writer. */
	public static class Writer extends HTTP2GoAway implements HTTP2Frame.Writer {
		
		private boolean produced = false;

		/** Constructor. */
		public Writer(int lastStreamID, int errorCode, byte[] debugMessage) {
			this.lastStreamID = lastStreamID;
			this.errorCode = errorCode;
			this.debugMessage = debugMessage;
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
			int payloadLength = 8;
			if (debugMessage != null)
				payloadLength += debugMessage.length;
			if (payloadLength > maxFrameSize)
				payloadLength = maxFrameSize;
			int frameLength = HTTP2FrameHeader.LENGTH + payloadLength;
			byte[] buffer = cache.get(frameLength, true);
			HTTP2FrameHeader.write(buffer, 0, payloadLength, HTTP2FrameHeader.TYPE_GOAWAY, (byte)0, 0);
			DataUtil.Write32.BE.write(buffer, HTTP2FrameHeader.LENGTH, lastStreamID);
			DataUtil.Write32.BE.write(buffer, HTTP2FrameHeader.LENGTH + 4, errorCode);
			if (debugMessage != null)
				System.arraycopy(debugMessage, 0, buffer, HTTP2FrameHeader.LENGTH + 8, payloadLength - 8);
			produced = true;
			return new ByteArray(buffer, 0, frameLength);
		}
		
	}
	
}
