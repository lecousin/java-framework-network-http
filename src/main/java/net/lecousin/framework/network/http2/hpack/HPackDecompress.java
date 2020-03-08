package net.lecousin.framework.network.http2.hpack;

import java.nio.ByteBuffer;

import net.lecousin.framework.io.data.SingleByteBitsBuffer;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.HTTP2PseudoHeaderHandler;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.util.Runnables.ConsumerThrows;
import net.lecousin.framework.util.Runnables.IntConsumerThrows;

/**
 * HPack decompression.
 * <a href="https://tools.ietf.org/html/rfc7541">Specification</a>
 */
public class HPackDecompress extends HPack {

	private Consumer consumer = null;

	/** Constructor. */
	public HPackDecompress(int maximumDynamicTableSize) {
		super(maximumDynamicTableSize);
	}
	
	/** Consume compressed headers data. */
	public void consume(ByteBuffer data, MimeHeaders headers, HTTP2PseudoHeaderHandler pseudoHeaderHandler) throws HTTP2Error {
		HTTP2PseudoHeaderHandler headerHandler = (name, value) -> {
			if (name.startsWith(":"))
				pseudoHeaderHandler.accept(name, value);
			else
				headers.addRawValue(name, value);
		};
		while (data.hasRemaining()) {
			if (consumer == null) consumer = new InitialConsumer();
			consumer.consume(data, headerHandler);
		}
	}
	
	private interface Consumer {
		
		void consume(ByteBuffer data, HTTP2PseudoHeaderHandler headerHandler) throws HTTP2Error;
		
	}
	
	private class InitialConsumer implements Consumer {
		
		@Override
		public void consume(ByteBuffer data, HTTP2PseudoHeaderHandler headerHandler) throws HTTP2Error {
			byte b = data.get();
			
			if ((b & 0x80) == 0x80) {
				// start with 1
				// Indexed Header Field Representation
				if ((b & 0x7F) == 0x7F) {
					consumer = new IntegerConsumer(0x7F, index -> {
						addIndexedHeaderField(index, headerHandler);
						consumer = null;
					});
					return;
				}
				addIndexedHeaderField(b & 0x7F, headerHandler);
				return;
			}
			// start with 0
			if ((b & 0x40) == 0x40) {
				// start with 01
				int index = b & 0x3F;
				if (index == 0) {
					// Literal Header Field with Incremental Indexing -- New Name
					consumer = new LiteralConsumer(name ->
						consumer = new LiteralConsumer(value -> {
							headerHandler.accept(name, value);
							dynTable.add(name, value);
							consumer = null;
						})
					);
					return;
				}
				// Literal Header Field with Incremental Indexing -- Indexed
				if (index == 0x3F) {
					consumer = new IntegerConsumer(0x3F, i -> {
						String name = getName(i);
						consumer = new LiteralConsumer(value -> {
							headerHandler.accept(name, value);
							dynTable.add(name, value);
							consumer = null;
						});
					});
					return;
				}
				String name = getName(index);
				consumer = new LiteralConsumer(value -> {
					headerHandler.accept(name, value);
					dynTable.add(name, value);
					consumer = null;
				});
				return;
			}
			// start with 00
			if ((b & 0x20) == 0x20) {
				// start with 001
				consumer = new IntegerConsumer(0x1F, size -> {
					if (size > initialMaximumDynamicTableSize)
						throw new HTTP2Error(true, HTTP2Error.Codes.COMPRESSION_ERROR, "Invalid dynamic table size");
					dynTable.updateSize(size);
					consumer = null;
				});
				return;
			}
			// start with 000
			if ((b & 0x10) == 0x10) {
				// start with 0001
				int index = b & 0xF;
				if (index == 0) {
					// Literal Header Field Never Indexed -- New Name
					consumer = new LiteralConsumer(name ->
						consumer = new LiteralConsumer(value -> {
							headerHandler.accept(name, value);
							consumer = null;
						})
					);
					return;
				}
				// Literal Header Field Never Indexed -- Indexed Name
				if (index == 0xF) {
					consumer = new IntegerConsumer(0x3F, i -> {
						String name = getName(i);
						consumer = new LiteralConsumer(value -> {
							headerHandler.accept(name, value);
							consumer = null;
						});
					});
					return;
				}
				String name = getName(index);
				consumer = new LiteralConsumer(value -> {
					headerHandler.accept(name, value);
					consumer = null;
				});
				return;
			}
			// start with 0000
			int index = b & 0xF;
			if (index == 0) {
				// Literal Header Field without Indexing -- New Name
				consumer = new LiteralConsumer(name ->
					consumer = new LiteralConsumer(value -> {
						headerHandler.accept(name, value);
						consumer = null;
					})
				);
				return;
			}
			// Literal Header Field without Indexing -- Indexed Name
			if (index == 0xF) {
				consumer = new IntegerConsumer(0x3F, i -> {
					String name = getName(i);
					consumer = new LiteralConsumer(value -> {
						headerHandler.accept(name, value);
						consumer = null;
					});
				});
				return;
			}
			String name = getName(index);
			consumer = new LiteralConsumer(value -> {
				headerHandler.accept(name, value);
				consumer = null;
			});
		}
	}
	
	private static class IntegerConsumer implements Consumer {
		private int index;
		private int m = 0;
		private IntConsumerThrows<HTTP2Error> onDone;
		
		private IntegerConsumer(int initialValue, IntConsumerThrows<HTTP2Error> onDone) {
			index = initialValue;
			this.onDone = onDone;
		}
		
		@Override
		public void consume(ByteBuffer data, HTTP2PseudoHeaderHandler headerHandler) throws HTTP2Error {
			do {
				byte b = data.get();
				index += (b & 0x7F) << m;
				m += 7;
				if ((b & 0x80) == 0) {
					// the end
					onDone.accept(index);
					return;
				}
			} while (data.hasRemaining());
		}
	}
	
	private static class LiteralConsumer implements Consumer {
		private LiteralConsumer(ConsumerThrows<String, HTTP2Error> onDone) {
			this.onDone = onDone;
		}
		
		private ConsumerThrows<String, HTTP2Error> onDone;
		private int m;
		private boolean useHuffman;
		private int strLength = 0;
		private char[] str;
		private int strPos = 0;
		private int huffStrPos = 0;
		private HPackHuffmanDecompress.Node huffmanNode;
		
		@Override
		public void consume(ByteBuffer data, HTTP2PseudoHeaderHandler headerHandler) throws HTTP2Error {
			do {
				byte b = data.get();
				if (str == null)
					consumeLength(b);
				else if (consumeString(b))
					return;
			} while (data.hasRemaining());
		}
		
		private void consumeLength(byte b) {
			if (strLength == 0) {
				useHuffman = (b & 0x80) == 0x80;
				strLength = b & 0x7F;
				if (strLength == 0x7F)
					m = 0;
				else
					str = new char[useHuffman ? strLength * 3 : strLength];
				return;
			}
			strLength += (b & 0x7F) << m;
			m += 7;
			if ((b & 0x80) == 0) {
				// the end
				str = new char[strLength];
			}
		}
		
		private boolean consumeString(byte b) throws HTTP2Error {
			// string data
			if (!useHuffman) {
				str[strPos++] = (char)(b & 0xFF);
				if (strPos == strLength) {
					onDone.accept(new String(str));
					return true;
				}
				return false;
			}
			// huffman
			SingleByteBitsBuffer.Readable bits = new SingleByteBitsBuffer.Readable.BigEndian(b);
			if (huffmanNode == null) huffmanNode = HPackHuffmanDecompress.root;
			do {
				Object o = huffmanNode.get(bits);
				if (o instanceof HPackHuffmanDecompress.Node) {
					huffmanNode = (HPackHuffmanDecompress.Node)o;
					break;
				}
				if (huffStrPos == str.length) {
					char[] s = new char[str.length * 2];
					System.arraycopy(str, 0, s, 0, str.length);
					str = s;
				}
				str[huffStrPos++] = ((Character)o).charValue();
				huffmanNode = HPackHuffmanDecompress.root;
			} while (bits.hasRemaining());
			if (++strPos == strLength) {
				onDone.accept(new String(str, 0, huffStrPos));
				return true;
			}
			return false;
		}
	}
	
}
