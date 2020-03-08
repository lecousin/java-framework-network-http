package net.lecousin.framework.network.http2.server;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.frame.HTTP2Frame;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;

interface StreamHandler {
	
	/** Start processing a frame. Return false if nothing else should be done.
	 * if payload length is 0, the method MUST process the frame and consumeFramePayload
	 * won't be called.
	 */
	boolean startFrame(ClientStreamsManager clientManager, HTTP2FrameHeader header);
	
	void consumeFramePayload(ClientStreamsManager clientManager, ByteBuffer data);
	
	abstract static class Default implements StreamHandler {
		
		protected int payloadPos = 0;
		protected HTTP2Frame.Reader frame;
		protected HTTP2Frame.Reader.Consumer payloadConsumer;
		
		@Override
		public void consumeFramePayload(
			ClientStreamsManager clientManager, ByteBuffer data
		) {
			HTTP2FrameHeader header = (HTTP2FrameHeader)clientManager.client.getAttribute(HTTP2ServerProtocol.ATTRIBUTE_FRAME_HEADER);
			int dataPos = data.position();
			AsyncSupplier<Boolean, HTTP2Error> consumption = payloadConsumer.consume(data);
			consumption.onDone(endOfFrame -> {
				payloadPos += data.position() - dataPos;
				if (payloadPos == header.getPayloadLength()) {
					if (!endOfFrame) {
						// TODO error
					}
					onEndOfPayload(clientManager, header);
					frame = null;
					payloadConsumer = null;
					payloadPos = 0;
					clientManager.server.endOfFrame(clientManager.client, data);
					return;
				}
				if (payloadPos > header.getPayloadLength()) {
					// TODO error
				}
				// more payload data expected
				if (data.hasRemaining()) {
					// TODO error
				}
				clientManager.server.bufferCache.free(data);
				try { clientManager.client.waitForData(clientManager.server.getReceiveDataTimeout()); }
				catch (ClosedChannelException e) { /* ignore. */ }
			}, error -> {
				// TODO
			}, cancel -> {
				// TODO
			});
		}
		
		protected abstract void onEndOfPayload(ClientStreamsManager clientManager, HTTP2FrameHeader header);
		
	}
}
