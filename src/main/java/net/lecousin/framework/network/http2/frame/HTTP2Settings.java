package net.lecousin.framework.network.http2.frame;

import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.io.util.DataUtil;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.http2.HTTP2Error;

/** Settings frame. */
public class HTTP2Settings implements HTTP2Frame {
	
	public static final byte FLAG_ACK = 0x01;
	
	public HTTP2Settings() {
	}
	
	public HTTP2Settings(HTTP2Settings copy) {
		this.headerTableSize = copy.headerTableSize;
		this.enablePush = copy.enablePush;
		this.maxConcurrentStreams = copy.maxConcurrentStreams;
		this.windowSize = copy.windowSize;
		this.maxFrameSize = copy.maxFrameSize;
		this.maxHeaderListSize = copy.maxHeaderListSize;
	}
	
	@Override
	public byte getType() {
		return HTTP2FrameHeader.TYPE_SETTINGS;
	}

	/** Identifier values for a setting. */
	public static final class Identifiers {
		
		private Identifiers() {
			/* no instance */
		}
		
		public static final int HEADER_TABLE_SIZE		= 0x0001;
		public static final int ENABLE_PUSH				= 0x0002;
		public static final int MAX_CONCURRENT_STREAMS	= 0x0003;
		public static final int INITIAL_WINDOW_SIZE		= 0x0004;
		public static final int MAX_FRAME_SIZE			= 0x0005;
		public static final int MAX_HEADER_LIST_SIZE	= 0x0006;
		
		public static final int LOWEST_KNOWN = 1;
		public static final int HIGHEST_KNOWN = 6;
		
	}
	
	/** Initial values for settings. */
	public static final class DefaultValues {
		
		private DefaultValues() {
			/* no instance */
		}

		public static final long HEADER_TABLE_SIZE		= 4096;
		public static final long ENABLE_PUSH			= 1;
		public static final long MAX_CONCURRENT_STREAMS	= -1; // no limit by default
		public static final long INITIAL_WINDOW_SIZE	= 65535;
		public static final long MAX_FRAME_SIZE			= 16384; // min is 16384, max is 16777215, else this is a protocol error
		public static final long MAX_HEADER_LIST_SIZE	= -1; // unlimited by default
		
		/** Get default value for given id. */
		public static long get(int id) {
			switch (id) {
			case Identifiers.HEADER_TABLE_SIZE: return HEADER_TABLE_SIZE;
			case Identifiers.ENABLE_PUSH: return ENABLE_PUSH;
			case Identifiers.MAX_CONCURRENT_STREAMS: return MAX_CONCURRENT_STREAMS;
			case Identifiers.INITIAL_WINDOW_SIZE: return INITIAL_WINDOW_SIZE;
			case Identifiers.MAX_FRAME_SIZE: return MAX_FRAME_SIZE;
			case Identifiers.MAX_HEADER_LIST_SIZE: return MAX_HEADER_LIST_SIZE;
			default: return -2;
			}
		}
		
	}
	
	protected Long headerTableSize;
	protected Boolean enablePush;
	protected Long maxConcurrentStreams;
	protected Long windowSize;
	protected Long maxFrameSize;
	protected Long maxHeaderListSize;
	
	public long getHeaderTableSize() {
		return headerTableSize != null ? headerTableSize.longValue() : DefaultValues.HEADER_TABLE_SIZE;
	}
	
	public HTTP2Settings setHeaderTableSize(long headerTableSize) {
		this.headerTableSize = Long.valueOf(headerTableSize);
		return this;
	}
	
	public boolean isEnablePush() {
		return enablePush != null ? enablePush.booleanValue() : DefaultValues.ENABLE_PUSH != 0;
	}
	
	public HTTP2Settings setEnablePush(boolean enablePush) {
		this.enablePush = Boolean.valueOf(enablePush);
		return this;
	}
	
	public long getMaxConcurrentStreams() {
		return maxConcurrentStreams != null ? maxConcurrentStreams.longValue() : DefaultValues.MAX_CONCURRENT_STREAMS;
	}
	
	public HTTP2Settings setMaxConcurrentStreams(long maxConcurrentStreams) {
		this.maxConcurrentStreams = Long.valueOf(maxConcurrentStreams);
		return this;
	}
	
	public long getWindowSize() {
		return windowSize != null ? windowSize.longValue() : DefaultValues.INITIAL_WINDOW_SIZE;
	}
	
	public HTTP2Settings setWindowSize(long windowSize) {
		this.windowSize = Long.valueOf(windowSize);
		return this;
	}
	
	public long getMaxFrameSize() {
		return maxFrameSize != null ? maxFrameSize.longValue() : DefaultValues.MAX_FRAME_SIZE;
	}
	
	public HTTP2Settings setMaxFrameSize(long maxFrameSize) {
		this.maxFrameSize = Long.valueOf(maxFrameSize);
		return this;
	}
	
	public long getMaxHeaderListSize() {
		return maxHeaderListSize != null ? maxHeaderListSize.longValue() : DefaultValues.MAX_HEADER_LIST_SIZE;
	}

	public HTTP2Settings setMaxHeaderListSize(long maxHeaderListSize) {
		this.maxHeaderListSize = Long.valueOf(maxHeaderListSize);
		return this;
	}
	
	/** Return the value of the given identifier, or -2 if unknown. */
	public long get(int id) {
		switch (id) {
		case Identifiers.HEADER_TABLE_SIZE: return getHeaderTableSize();
		case Identifiers.ENABLE_PUSH: return isEnablePush() ? 1 : 0;
		case Identifiers.MAX_CONCURRENT_STREAMS: return getMaxConcurrentStreams();
		case Identifiers.INITIAL_WINDOW_SIZE: return getWindowSize();
		case Identifiers.MAX_FRAME_SIZE: return getMaxFrameSize();
		case Identifiers.MAX_HEADER_LIST_SIZE: return getMaxHeaderListSize();
		default: return -2;
		}
	}
	
	/** Set the value of the given identifier. If unknown it is ignored. */
	public void set(int id, long value) {
		switch (id) {
		case Identifiers.HEADER_TABLE_SIZE:
			headerTableSize = Long.valueOf(value);
			break;
		case Identifiers.ENABLE_PUSH:
			enablePush = Boolean.valueOf(value != 0);
			break;
		case Identifiers.MAX_CONCURRENT_STREAMS:
			maxConcurrentStreams = Long.valueOf(value);
			break;
		case Identifiers.INITIAL_WINDOW_SIZE:
			windowSize = Long.valueOf(value);
			break;
		case Identifiers.MAX_FRAME_SIZE:
			maxFrameSize = Long.valueOf(value);
			break;
		case Identifiers.MAX_HEADER_LIST_SIZE:
			maxHeaderListSize = Long.valueOf(value);
			break;
		default: // ignore
		}
	}
	
	/** Apply present values from the given settings. */
	public void set(HTTP2Settings settings) {
		if (settings.headerTableSize != null)
			headerTableSize = settings.headerTableSize;
		if (settings.enablePush != null)
			enablePush = settings.enablePush;
		if (settings.maxConcurrentStreams != null)
			maxConcurrentStreams = settings.maxConcurrentStreams;
		if (settings.windowSize != null)
			windowSize = settings.windowSize;
		if (settings.maxFrameSize != null)
			maxFrameSize = settings.maxFrameSize;
		if (settings.maxHeaderListSize != null)
			maxHeaderListSize = settings.maxHeaderListSize;
	}
	
	/** HTTP/2 settings frame reader. */
	public static class Reader extends HTTP2Settings implements HTTP2Frame.Reader {

		private int size;
		
		/** Constructor. */
		public Reader(HTTP2FrameHeader header) {
			this(header.getPayloadLength());
		}
		
		/** Constructor. */
		public Reader(int size) {
			this.size = size;
		}
		
		@Override
		public Consumer createConsumer() {
			return new Consumer() {
				private int pos = 0;
				private int id = 0;
				private long value = 0;
				
				@Override
				public AsyncSupplier<Boolean, HTTP2Error> consume(ByteBuffer data) {
					int p = pos / 6;
					while (data.hasRemaining()) {
						byte b = data.get();
						if (p < 2) {
							id = (id << 8) | (b & 0xFF);
							p++;
							continue;
						}
						value = (value << 8) | (b & 0xFF);
						if (++p == 6) {
							set(id, value);
							pos += 6;
							if (pos == size)
								return new AsyncSupplier<>(Boolean.TRUE, null);
							p = 0;
							id = 0;
							value = 0;
						}
					}
					return new AsyncSupplier<>(Boolean.FALSE, null);
				}
			};
		}
		
	}
	
	public Writer writer() {
		return new Writer(this);
	}

	/** HTTP/2 settings frame writer. */
	public static class Writer extends HTTP2Settings implements HTTP2Frame.Writer {
		
		private boolean onlyNonDefaultValue;
		private boolean produced = false;
		
		/** Constructor. */
		public Writer() {
		}
		
		/** Constructor. */
		public Writer(HTTP2Settings copy) {
			super(copy);
		}
		
		/** Constructor. */
		public Writer(boolean onlyNonDefaultValue) {
			this.onlyNonDefaultValue = onlyNonDefaultValue;
		}
		
		/** Constructor. */
		public Writer(HTTP2Settings copy, boolean onlyNonDefaultValue) {
			super(copy);
			this.onlyNonDefaultValue = onlyNonDefaultValue;
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
			int nb;
			if (onlyNonDefaultValue) {
				nb = 0;
				for (int id = Identifiers.LOWEST_KNOWN; id <= Identifiers.HIGHEST_KNOWN; ++id)
					if (get(id) != DefaultValues.get(id))
						nb++;
			} else {
				nb = Identifiers.HIGHEST_KNOWN - Identifiers.LOWEST_KNOWN + 1;
			}
			int len = nb * 6;
			HTTP2FrameHeader header = new HTTP2FrameHeader(len, HTTP2FrameHeader.TYPE_SETTINGS, (byte)0, 0);
			byte[] b = cache.get(HTTP2FrameHeader.LENGTH + len, true);
			header.write(b, 0);
			int pos = HTTP2FrameHeader.LENGTH ;
			for (int id = Identifiers.LOWEST_KNOWN; id <= Identifiers.HIGHEST_KNOWN; ++id) {
				long value = get(id);
				if (!onlyNonDefaultValue || value != DefaultValues.get(id)) {
					DataUtil.Write16U.BE.write(b, pos, id);
					DataUtil.Write32U.BE.write(b, pos + 2, value);
					pos += 6;
				}
			}
			produced = true;
			return new ByteArray(b, 0, HTTP2FrameHeader.LENGTH + len);
		}
		
	}
	
}
