package net.lecousin.framework.network.http2.frame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.util.Pair;

/** Data frame. */
public abstract class HTTP2Data implements HTTP2Frame {

	public static final byte FLAG_END_STREAM	= 0x01;
	public static final byte FLAG_PADDED		= 0x08;
	
	@Override
	public byte getType() {
		return HTTP2FrameHeader.TYPE_DATA;
	}
	
	/** HTTP/2 DATA frame reader. */
	public static class Reader extends HTTP2Data implements HTTP2Frame.Reader {
		
		private HTTP2FrameHeader header;
		private AsyncConsumer<ByteBuffer, IOException> bodyConsumer;
		
		/** Constructor. */
		public Reader(HTTP2FrameHeader header, AsyncConsumer<ByteBuffer, IOException> bodyConsumer) {
			this.header = header;
			this.bodyConsumer = bodyConsumer;
		}
		
		@Override
		public Consumer createConsumer() {
			return new Consumer() {
				private int pos = 0;
				
				@Override
				public AsyncSupplier<Boolean, HTTP2Error> consume(ByteBuffer data) {
					ByteBuffer b;
					int len = data.remaining();
					if (len <= header.getPayloadLength() - pos) {
						b = data;
					} else {
						len = header.getPayloadLength() - pos;
						b = data.duplicate();
						b.limit(b.position() + len);
					}
					pos += len;
					b = b.asReadOnlyBuffer();
					IAsync<IOException> consume = bodyConsumer.consume(b);
					AsyncSupplier<Boolean, HTTP2Error> result = new AsyncSupplier<>();
					int l = len;
					consume.onDone(() -> {
						data.position(data.position() + l);
						result.unblockSuccess(Boolean.valueOf(pos == header.getPayloadLength()));
					}, result, ioErr -> {
						// TODO log it
						return new HTTP2Error(header.getStreamId(), HTTP2Error.Codes.INTERNAL_ERROR, ioErr.getMessage());
					});
					return result;
				}
			};
		}
		
	}
	
	/** HTTP/2 Data frame writer. */
	public static class Writer extends HTTP2Data implements HTTP2Frame.Writer {

		private int streamId;
		private boolean isEndOfStream = false;
		private LinkedList<ByteBuffer> data = new LinkedList<>();
		private int dataSize = 0;
		private Consumer<Pair<List<ByteBuffer>, Integer>> onDataProduced;

		/** Constructor. */
		public Writer(int streamId, ByteBuffer firstData, boolean endOfStream, Consumer<Pair<List<ByteBuffer>, Integer>> onDataProduced) {
			this.streamId = streamId;
			if (firstData != null) {
				data.add(firstData);
				dataSize += firstData.remaining();
			}
			this.isEndOfStream = endOfStream;
			this.onDataProduced = onDataProduced;
		}
		
		@Override
		public int getStreamId() {
			return streamId;
		}
		
		@Override
		public boolean canProduceMore() {
			return isEndOfStream || !data.isEmpty();
		}
		
		@Override
		public boolean canProduceSeveralFrames() {
			return true;
		}
		
		/** Set end of stream to true, return true if it was possible, false if not possible. */
		public boolean setEndOfStream() {
			synchronized (data) {
				if (data.isEmpty())
					return false;
				isEndOfStream = true;
				return true;
			}
		}
		
		/** Add data to send, return true if it was possible, false if not possible. */
		public boolean addData(ByteBuffer d) {
			synchronized (data) {
				if (data.isEmpty())
					return false;
				data.add(d);
				dataSize += d.remaining();
				return true;
			}
		}
		
		@Override
		public ByteArray produce(int maxFrameSize, ByteArrayCache cache) {
			int payloadLength = Math.min(dataSize, maxFrameSize);
			byte[] b = cache.get(HTTP2FrameHeader.LENGTH + payloadLength, true);
			byte flags = 0;
			int pos = 0;
			LinkedList<ByteBuffer> produced = new LinkedList<>();
			synchronized (data) {
				while (!data.isEmpty() && pos < payloadLength) {
					ByteBuffer bb = data.getFirst();
					int len = Math.min(bb.remaining(), payloadLength - pos);
					bb.get(b, HTTP2FrameHeader.LENGTH + pos, len);
					if (!bb.hasRemaining())
						produced.add(data.removeFirst());
					pos += len;
					dataSize -= len;
				}
				if (isEndOfStream && data.isEmpty()) {
					flags |= HTTP2Data.FLAG_END_STREAM;
					isEndOfStream = false;
				}
			}
			HTTP2FrameHeader.write(b, 0, payloadLength, HTTP2FrameHeader.TYPE_DATA, flags, streamId);
			if (onDataProduced != null)
				onDataProduced.accept(new Pair<>(produced, Integer.valueOf(pos)));
			return new ByteArray(b, 0, HTTP2FrameHeader.LENGTH + payloadLength);
		}
		
	}
	
}
