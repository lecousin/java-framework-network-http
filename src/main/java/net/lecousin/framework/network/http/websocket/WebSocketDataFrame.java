package net.lecousin.framework.network.http.websocket;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;

/** Frame used for the web socket protocol. */
public class WebSocketDataFrame {
	
	public static final int TYPE_TEXT = 1;
	public static final int TYPE_BINARY = 2;
	public static final int TYPE_CLOSE = 8;
	public static final int TYPE_PING = 9;
	public static final int TYPE_PONG = 10;
	
	/** Constructor. */
	public WebSocketDataFrame() {
		message = new IOInMemoryOrFile(32768, Task.PRIORITY_NORMAL, "WebSocket Data Frame");
	}
	
	private int messageType = 0;
	private int headerRead = 0;
	private byte byte1;
	private boolean maskPresent;
	private int payloadLengthBits;
	private long payloadLength;
	private int maskRead = 0;
	private byte[] maskValue = null;
	private long messageRead = 0;
	private IOInMemoryOrFile message;
	
	public IOInMemoryOrFile getMessage() {
		return message;
	}
	
	public int getMessageType() {
		return messageType;
	}
	
	/** Parse the given received data, return true if the frame is complete, false if more data is needed. */
	public boolean read(ByteBuffer data) throws IOException {
		if (headerRead == 0) {
			byte1 = data.get();
			headerRead++;
			if (messageType == 0)
				messageType = (byte1 & 0xF);
			if (!data.hasRemaining()) return false;
		}
		if (headerRead == 1) {
			if (!data.hasRemaining()) return false;
			byte b = data.get();
			headerRead++;
			maskPresent = (b & 0x80) != 0;
			payloadLength = (b & 0x7F);
			if (payloadLength == 126) {
				payloadLengthBits = 16;
				payloadLength = 0;
			} else if (payloadLength == 127) {
				payloadLengthBits = 64;
				payloadLength = 0;
			} else
				payloadLengthBits = 7;
		}
		while (payloadLengthBits == 16 && headerRead < 4) {
			if (!data.hasRemaining()) return false;
			byte b = data.get();
			if (headerRead == 2)
				payloadLength = (b & 0xFF) << 8;
			else
				payloadLength |= (b & 0xFF);
			headerRead++;
		}
		while (payloadLengthBits == 64 && headerRead < 10) {
			if (!data.hasRemaining()) return false;
			byte b = data.get();
			payloadLength |= (b & 0xFF) << (8 * (7 + 2 - headerRead));
			headerRead++;
		}
		if (maskPresent && maskValue == null)
			maskValue = new byte[4];
		while (maskPresent && maskRead < 4) {
			if (!data.hasRemaining()) return false;
			maskValue[maskRead++] = data.get();
		}
		if (messageRead == payloadLength)
			return endOfFrame();
		if (!data.hasRemaining()) return false;
		int nb = data.remaining();
		if (messageRead + nb > payloadLength) nb = (int)(payloadLength - messageRead);
		byte[] buf = new byte[nb];
		data.get(buf);
		if (maskPresent) {
			for (int i = 0; i < nb; i++,messageRead++)
				buf[i] = (byte)((buf[i] & 0xFF) ^ (maskValue[(int)(messageRead % 4)] & 0xFF));
		} else
			messageRead += nb;
		message.writeSync(ByteBuffer.wrap(buf));
		if (messageRead == payloadLength)
			return endOfFrame();
		return false;
	}
	
	private boolean endOfFrame() {
		if ((byte1 & 0x80) != 0)
			// end of message => process it
			return true;
		// end of this frame, next frame coming
		// prepare next frame
		headerRead = 0;
		maskValue = null;
		messageRead = 0;
		return false;
	}
	
}
