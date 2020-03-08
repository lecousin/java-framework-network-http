package net.lecousin.framework.network.http2.frame;

import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.io.util.DataUtil;
import net.lecousin.framework.memory.ByteArrayCache;

/** GOAWAY frame. */
public abstract class HTTP2GoAway implements HTTP2Frame {
	
	protected int lastStreamID;
	protected int errorCode;
	protected byte[] debugMessage;
	
	@Override
	public byte getType() {
		return HTTP2FrameHeader.TYPE_GOAWAY;
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
