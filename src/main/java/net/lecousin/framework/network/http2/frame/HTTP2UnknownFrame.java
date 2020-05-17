package net.lecousin.framework.network.http2.frame;

import net.lecousin.framework.concurrent.async.AsyncSupplier;

public abstract class HTTP2UnknownFrame implements HTTP2Frame {

	public static class Reader extends HTTP2UnknownFrame implements HTTP2Frame.Reader {
		
		private HTTP2FrameHeader header;
		private int payloadPos = 0;
		
		public Reader(HTTP2FrameHeader header) {
			this.header = header;
		}
		
		@Override
		public byte getType() {
			return header.getType();
		}
		
		@Override
		public Consumer createConsumer() {
			return data -> {
				int l = Math.min(data.remaining(), header.getPayloadLength() - payloadPos);
				data.position(data.position() + l);
				payloadPos += l;
				return new AsyncSupplier<>(Boolean.valueOf(payloadPos == header.getPayloadLength()), null);
			};
		}
		
	}
	
}
