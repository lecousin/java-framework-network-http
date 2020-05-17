package net.lecousin.framework.network.http2.frame;

import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.network.http2.HTTP2Error;

/** HTTP/2 priority frame. */
public abstract class HTTP2Priority implements HTTP2Frame {

	public static final int LENGTH = 5;
	
	protected boolean exclusiveDependency;
	protected int streamDependency;
	protected int dependencyWeight;
	
	@Override
	public byte getType() {
		return HTTP2FrameHeader.TYPE_PRIORITY;
	}
	
	/** Return true if this frame specify priority information. */
	public boolean hasPriority() {
		return true;
	}
	
	public boolean isExclusiveDependency() {
		return exclusiveDependency;
	}
	
	public int getStreamDependency() {
		return streamDependency;
	}
	
	public int getDependencyWeight() {
		return dependencyWeight;
	}
	
	/** HTTP/2 priority frame reader. */
	public static class Reader extends HTTP2Priority implements HTTP2Frame.Reader {
		
		/** Constructor. */
		public Reader() {
		}
		
		@Override
		public Consumer createConsumer() {
			return new Consumer() {
				private int pos = 0;
				
				@Override
				public AsyncSupplier<Boolean, HTTP2Error> consume(ByteBuffer data) {
					while (data.hasRemaining()) {
						byte b = data.get();
						if (pos == 0) {
							exclusiveDependency = (b & 0x80) == 0x80;
							streamDependency = (b & 0x7F) << 24;
							pos++;
						} else if (pos == 4) {
							dependencyWeight = b & 0xFF;
							return new AsyncSupplier<>(Boolean.TRUE, null);
						} else {
							streamDependency = (b & 0xFF) << (8 * (3 - pos));
							pos++;
						}
					}
					return new AsyncSupplier<>(Boolean.FALSE, null);
				}
			};
		}
		
	}
	
}
