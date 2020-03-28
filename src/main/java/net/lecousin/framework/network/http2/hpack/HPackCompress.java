package net.lecousin.framework.network.http2.hpack;

import net.lecousin.framework.io.data.Bytes;
import net.lecousin.framework.util.Pair;

/**
 * HPack decompression.
 * <a href="https://tools.ietf.org/html/rfc7541">Specification</a>
 */
public class HPackCompress extends HPack {

	/** Constructor. */
	public HPackCompress(int maximumDynamicTableSize) {
		super(maximumDynamicTableSize);
	}
	
	/**
	 * Compress the given header into the buffer.
	 * 
	 * @param name header name
	 * @param value header value
	 * @param indexIfNewName true if the header can be indexed into the dynamic table in case the name and value are new
	 * @param indexIfNewValue true if the header can be indexed into the dynamic table in case the name is known but value is new
	 * @param buffer store compressed data
	 * @return true if the compressed data has been written into buffer, false if more space is needed
	 */
	public boolean compress(String name, String value, boolean indexIfNewName, boolean indexIfNewValue, Bytes.Writable buffer) {
		Pair<Integer, Integer> indexed = search(name, value);
		if (indexed.getValue2() != null)
			return writeIndexed(indexed.getValue2().intValue(), buffer);
		if (indexed.getValue1() != null) {
			if (!writeNameIndexed(indexed.getValue1().intValue(), value, indexIfNewValue, buffer))
				return false;
			if (indexIfNewValue)
				dynTable.add(name, value);
			return true;
		}
		
		if (!writeNew(name, value, indexIfNewName, buffer))
			return false;
		if (indexIfNewName)
			dynTable.add(name, value);
		return true;
	}
	
	private static boolean writeIndexed(int index, Bytes.Writable buffer) {
		int size = getIntegerSize(index, 0x7F);
		if (buffer.remaining() < size)
			return false;
		writeInteger(index, 0x80, 0x7F, buffer);
		return true;
	}
	
	private static boolean writeNameIndexed(int nameIndex, String value, boolean indexValue, Bytes.Writable buffer) {
		int valueLen = value.length();
		int valueSizeUncompressed = getIntegerSize(valueLen, 0x7F) + valueLen;
		int valueLenCompressed = HPackHuffmanCompress.getBytesToCompress(value);
		int valueSizeCompressed = getIntegerSize(valueLenCompressed, 0x7F) + valueLenCompressed;
		int size = getIntegerSize(nameIndex, indexValue ? 0x3F : 0x0F) + Math.min(valueSizeUncompressed, valueSizeCompressed);
		if (buffer.remaining() < size)
			return false;
		writeInteger(nameIndex, indexValue ? 0x40 : 0x00, indexValue ? 0x3F : 0x0F, buffer);
		if (valueSizeUncompressed <= valueSizeCompressed) {
			// not using huffman
			writeInteger(valueLen, 0x00, 0x7F, buffer);
			for (int i = 0; i < valueLen; ++i)
				buffer.put((byte)value.charAt(i));
		} else {
			// using huffman
			writeInteger(valueLenCompressed, 0x80, 0x7F, buffer);
			HPackHuffmanCompress.compress(value, buffer);
		}
		return true;
	}
	
	private static boolean writeNew(String name, String value, boolean addToIndex, Bytes.Writable buffer) {
		int nameLen = name.length();
		int nameSizeUncompressed = getIntegerSize(nameLen, 0x7F) + nameLen;
		int nameLenCompressed = HPackHuffmanCompress.getBytesToCompress(name);
		int nameSizeCompressed = getIntegerSize(nameLenCompressed, 0x7F) + nameLenCompressed;
		int valueLen = value.length();
		int valueSizeUncompressed = getIntegerSize(valueLen, 0x7F) + valueLen;
		int valueLenCompressed = HPackHuffmanCompress.getBytesToCompress(value);
		int valueSizeCompressed = getIntegerSize(valueLenCompressed, 0x7F) + valueLenCompressed;
		int size = 1 + Math.min(nameSizeUncompressed, nameSizeCompressed) + Math.min(valueSizeUncompressed, valueSizeCompressed);
		if (buffer.remaining() < size)
			return false;
		buffer.put(addToIndex ? (byte)0x40 : (byte)0x00);
		if (nameSizeUncompressed <= nameSizeCompressed) {
			// not using huffman
			writeInteger(nameLen, 0x00, 0x7F, buffer);
			for (int i = 0; i < nameLen; ++i)
				buffer.put((byte)name.charAt(i));
		} else {
			// using huffman
			writeInteger(nameLenCompressed, 0x80, 0x7F, buffer);
			HPackHuffmanCompress.compress(name, buffer);
		}
		if (valueSizeUncompressed <= valueSizeCompressed) {
			// not using huffman
			writeInteger(valueLen, 0x00, 0x7F, buffer);
			for (int i = 0; i < valueLen; ++i)
				buffer.put((byte)value.charAt(i));
		} else {
			// using huffman
			writeInteger(valueLenCompressed, 0x80, 0x7F, buffer);
			HPackHuffmanCompress.compress(value, buffer);
		}
		return true;
	}
	
	private static int getIntegerSize(int value, int firstByteMax) {
		if (value < firstByteMax)
			return 1;
		int size = 2;
		value -= firstByteMax;
		while (value > 0x7F) {
			value >>= 7;
			size++;
		}
		return size;
	}
	
	private static void writeInteger(int value, int initialByte, int firstByteMax, Bytes.Writable buffer) {
		if (value < firstByteMax) {
			buffer.put((byte)(initialByte | value));
			return;
		}
		buffer.put((byte)(initialByte | firstByteMax));
		value -= firstByteMax;
		do {
			byte b = (byte)(value & 0x7F);
			value >>= 7;
			if (value == 0) {
				buffer.put(b);
				return;
			}
			buffer.put((byte)(b | 0x80));
		} while (true);
	}
	
}
