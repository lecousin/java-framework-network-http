package net.lecousin.framework.network.http2.streams;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.frame.HTTP2Frame;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;

public interface StreamHandler {
	
	/** Return the stream id. */
	int getStreamId();
	
	/** Start processing a frame. Return false if nothing else should be done.
	 * if payload length is 0, the method MUST process the frame and consumeFramePayload
	 * won't be called.
	 */
	boolean startFrame(StreamsManager manager, HTTP2FrameHeader header);
	
	/** Consume frame. */
	void consumeFramePayload(StreamsManager manager, ByteBuffer data, Async<IOException> onConsumed);
	
	/** Called when the stream is closed or the remote connection is closed to free or unblock any remaining resources. */
	void closed();
	
	public abstract static class Default implements StreamHandler {
		
		protected int payloadPos = 0;
		protected HTTP2Frame.Reader frame;
		protected HTTP2Frame.Reader.Consumer payloadConsumer;
		
		@Override
		public void consumeFramePayload(StreamsManager manager, ByteBuffer data, Async<IOException> onConsumed) {
			boolean trace = manager.getLogger().trace();
			HTTP2FrameHeader header = manager.getCurrentFrameHeader();
			int dataPos = data.position();
			if (payloadConsumer == null) {
				if (manager.getLogger().debug())
					manager.getLogger().debug("No consumer => skip frame payload");
				new SkipFrame(getStreamId()).consumeFramePayload(manager, data, onConsumed);
				return;
			}
			if (trace)
				manager.getLogger().trace("Consuming frame payload using " + payloadConsumer);
			AsyncSupplier<Boolean, HTTP2Error> consumption = payloadConsumer.consume(data);
			consumption.thenDoOrStart("HTTP/2 frame payload consumed", Priority.NORMAL, () -> {
				if (!consumption.isSuccessful()) {
					if (consumption.hasError())
						error(consumption.getError(), manager, onConsumed);
					else
						error(new HTTP2Error(false, HTTP2Error.Codes.CANCEL, null), manager, onConsumed);
				}
				payloadPos += data.position() - dataPos;
				if (trace)
					manager.getLogger().trace("Frame payload consumed: " + payloadPos + " / " + header.getPayloadLength());
				if (payloadPos == header.getPayloadLength()) {
					if (!consumption.getResult().booleanValue()) {
						error(new HTTP2Error(false, HTTP2Error.Codes.INTERNAL_ERROR,
							"Payload consumer said it needs more data but full payload has been consumed: "
							+ payloadConsumer), manager, onConsumed);
						return;
					}
					onEndOfPayload(manager, header);
					frame = null;
					payloadConsumer = null;
					payloadPos = 0;
					manager.endOfFrame(data, onConsumed);
					return;
				}
				if (payloadPos > header.getPayloadLength()) {
					error(new HTTP2Error(false, HTTP2Error.Codes.INTERNAL_ERROR,
						"Payload consumer consumed more than payload: " + payloadConsumer),	manager, onConsumed);
					return;
				}
				// more payload data expected
				if (data.hasRemaining()) {
					error(new HTTP2Error(false, HTTP2Error.Codes.INTERNAL_ERROR,
						"Payload consumer didn't consume full data: " + payloadConsumer), manager, onConsumed);
					return;
				}
				onConsumed.unblock();
			});
		}
		
		protected abstract void error(HTTP2Error error, StreamsManager manager, Async<IOException> onConsumed);
		
		protected abstract void onEndOfPayload(StreamsManager manager, HTTP2FrameHeader header);
		
	}
}
