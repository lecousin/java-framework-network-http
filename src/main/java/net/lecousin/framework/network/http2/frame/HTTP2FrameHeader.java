package net.lecousin.framework.network.http2.frame;

import java.nio.ByteBuffer;

/**
 * HTTP/2 Frame header.
 * <p>
 * <a href="https://tools.ietf.org/html/rfc7540#section-4.1">Specification</a>:
 * </p>
 * <pre>
   All frames begin with a fixed 9-octet header followed by a variable-
   length payload.

    +-----------------------------------------------+
    |                 Length (24)                   |
    +---------------+---------------+---------------+
    |   Type (8)    |   Flags (8)   |
    +-+-------------+---------------+-------------------------------+
    |R|                 Stream Identifier (31)                      |
    +=+=============================================================+
    |                   Frame Payload (0...)                      ...
    +---------------------------------------------------------------+
    </pre>
 * 
 */
public class HTTP2FrameHeader {
	
	public static final int LENGTH = 9;
	
	public static final byte TYPE_DATA = 0x00;
	public static final byte TYPE_HEADERS = 0x01;
	public static final byte TYPE_PRIORITY = 0x02;
	public static final byte TYPE_RST_STREAM = 0x03;
	public static final byte TYPE_SETTINGS = 0x04;
	public static final byte TYPE_PUSH_PROMISE = 0x05;
	public static final byte TYPE_PING = 0x06;
	public static final byte TYPE_GOAWAY = 0x07;
	public static final byte TYPE_WINDOW_UPDATE = 0x08;
	public static final byte TYPE_CONTINUATION = 0x09;

	private int payloadLength;
	private byte type;
	private byte flags;
	private int streamId;
	
	/** Default constructor. */
	public HTTP2FrameHeader() {
	}
	
	/** Constructor. */
	public HTTP2FrameHeader(int payloadLength, byte type, byte flags, int streamId) {
		this.payloadLength = payloadLength;
		this.type = type;
		this.flags = flags;
		this.streamId = streamId;
	}
	
	
	public int getPayloadLength() {
		return payloadLength;
	}

	public byte getType() {
		return type;
	}

	public byte getFlags() {
		return flags;
	}

	public int getStreamId() {
		return streamId;
	}
	
	/** Return true if this header has the given flags set. */
	public boolean hasFlags(int flags) {
		return (this.flags & flags) == flags;
	}
	
	/** Create a consumer. Equivalent to a new Consumer(). */
	public Consumer createConsumer() {
		return new Consumer();
	}

	/** Consumer of bytes to read the HTTP/2 frame header.
	 * Returns true if the header has been fully consumed, false if more bytes are expected.
	 */
	public class Consumer {
		
		private int pos = 0;

		/** Consume bytes from the given data.
		 * @return true if the header has been fully consumed, false if more bytes are expected
		 */
		public boolean consume(ByteBuffer data) {
			while (data.hasRemaining()) {
				switch (pos++) {
				case 0:
					payloadLength = (data.get() & 0xFF) << 16;
					break;
				case 1:
					payloadLength |= (data.get() & 0xFF) << 8;
					break;
				case 2:
					payloadLength |= (data.get() & 0xFF);
					break;
				case 3:
					type = data.get();
					break;
				case 4:
					flags = data.get();
					break;
				case 5:
					streamId = (data.get() & 0x7F) << 24;
					break;
				case 6:
					streamId = (data.get() & 0xFF) << 16;
					break;
				case 7:
					streamId = (data.get() & 0xFF) << 8;
					break;
				case 8:
					streamId = (data.get() & 0xFF);
					return true;
				default: break; // not possible
				}
			}
			return false;
		}
		
	}
	
	/** Write this header into the given buffer. */
	public void write(byte[] buffer, int offset) {
		write(buffer, offset, payloadLength, type, flags, streamId);
	}
	
	/** Write a header into a buffer. */
	public static void write(byte[] buffer, int offset, int payloadLength, byte type, byte flags, int streamId) {
		buffer[offset++] = (byte)((payloadLength >> 16) & 0xFF);
		buffer[offset++] = (byte)((payloadLength >> 8) & 0xFF);
		buffer[offset++] = (byte)(payloadLength & 0xFF);
		buffer[offset++] = type;
		buffer[offset++] = flags;
		buffer[offset++] = (byte)((streamId >> 24) & 0xFF);
		buffer[offset++] = (byte)((streamId >> 16) & 0xFF);
		buffer[offset++] = (byte)((streamId >> 8) & 0xFF);
		buffer[offset] = (byte)(streamId & 0xFF);
	}
	
}
