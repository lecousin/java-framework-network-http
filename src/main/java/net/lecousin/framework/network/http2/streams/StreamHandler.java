package net.lecousin.framework.network.http2.streams;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.frame.HTTP2Frame;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;

public interface StreamHandler {
	
	/** Start processing a frame. Return false if nothing else should be done.
	 * if payload length is 0, the method MUST process the frame and consumeFramePayload
	 * won't be called.
	 */
	boolean startFrame(StreamsManager manager, HTTP2FrameHeader header);
	
	/** Consume frame. */
	void consumeFramePayload(StreamsManager manager, ByteBuffer data, Async<IOException> onConsumed);
	
	public abstract static class Default implements StreamHandler {
		
		protected int payloadPos = 0;
		protected HTTP2Frame.Reader frame;
		protected HTTP2Frame.Reader.Consumer payloadConsumer;
		
		@Override
		public void consumeFramePayload(StreamsManager manager, ByteBuffer data, Async<IOException> onConsumed) {
			HTTP2FrameHeader header = manager.getCurrentFrameHeader();
			int dataPos = data.position();
			if (manager.getLogger().debug())
				manager.getLogger().debug("Consuming frame payload using " + payloadConsumer);
			AsyncSupplier<Boolean, HTTP2Error> consumption = payloadConsumer.consume(data);
			consumption.onDone(endOfFrame -> {
				payloadPos += data.position() - dataPos;
				if (manager.getLogger().debug())
					manager.getLogger().debug("Frame payload consumed: " + payloadPos + " / " + header.getPayloadLength());
				if (payloadPos == header.getPayloadLength()) {
					if (!endOfFrame) {
						// TODO error
					}
					onEndOfPayload(manager, header);
					frame = null;
					payloadConsumer = null;
					payloadPos = 0;
					manager.endOfFrame(data, onConsumed);
					return;
				}
				if (payloadPos > header.getPayloadLength()) {
					// TODO error
				}
				// more payload data expected
				if (data.hasRemaining()) {
					// TODO error
				}
				onConsumed.unblock();
			}, error -> {
				// TODO
				manager.getLogger().error("Error consuming HTTP/2 payload", error);
			}, cancel -> {
				// TODO
			});
		}
		
		protected abstract void onEndOfPayload(StreamsManager manager, HTTP2FrameHeader header);
		
	}
}
