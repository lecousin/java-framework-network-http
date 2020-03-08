package net.lecousin.framework.network.http2.hpack;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;

import net.lecousin.framework.network.http2.HTTP2Constants;
import net.lecousin.framework.network.http2.HTTP2Error;
import net.lecousin.framework.network.http2.HTTP2PseudoHeaderHandler;
import net.lecousin.framework.util.Pair;

/**
 * HPack compression.
 * <a href="https://tools.ietf.org/html/rfc7541">Specification</a>
 */
public abstract class HPack {
	
	protected DynamicTable dynTable;
	protected int initialMaximumDynamicTableSize;
	
	/** Constructor. */
	public HPack(int maximumDynamicTableSize) {
		dynTable = new DynamicTable();
		dynTable.maximumSize = maximumDynamicTableSize;
		this.initialMaximumDynamicTableSize = maximumDynamicTableSize;
	}
	
	/** Update the maximum size of the dynamic table. */
	public void updateMaximumDynamicTableSize(int maximumSize) {
		dynTable.updateSize(maximumSize);
	}
	
	/** Dynamic table. */
	protected static class DynamicTable {
		
		// in order to optimize index storage on 7-bits:
		// static table size is 61
		// we can store up to 66 to dynamic table and stay on 7-bit
		// 66 * (32 + 8 + 16) = 3696
		
		private int currentSize = 0;
		private int maximumSize;
		private LinkedList<String> table = new LinkedList<>();
		
		protected void add(String name, String value) {
			int addLength = name.length() + value.length() + 32;
			if (addLength > maximumSize) {
				table.clear();
				currentSize = 0;
				return;
			}
			currentSize += addLength;
			while (currentSize > maximumSize)
				evictEntry();
			table.addFirst(value);
			table.addFirst(name);
		}
		
		protected void updateSize(int newMaxSize) {
			maximumSize = newMaxSize;
			while (currentSize > maximumSize)
				evictEntry();
		}
		
		protected void evictEntry() {
			currentSize -= table.removeLast().length();
			currentSize -= table.removeLast().length();
			currentSize -= 32;
		}
		
	}
	
	private static final String[] STATIC_TABLE = new String[] {
		HTTP2Constants.Headers.Request.Pseudo.AUTHORITY,null,
		HTTP2Constants.Headers.Request.Pseudo.METHOD,"GET",
		HTTP2Constants.Headers.Request.Pseudo.METHOD,"POST",
		HTTP2Constants.Headers.Request.Pseudo.PATH,"/",
		HTTP2Constants.Headers.Request.Pseudo.PATH,"/index.html",
		HTTP2Constants.Headers.Request.Pseudo.SCHEME,"http",
		HTTP2Constants.Headers.Request.Pseudo.SCHEME,"https",
		HTTP2Constants.Headers.Response.Pseudo.STATUS,"200",
		HTTP2Constants.Headers.Response.Pseudo.STATUS,"204",
		HTTP2Constants.Headers.Response.Pseudo.STATUS,"206",
		HTTP2Constants.Headers.Response.Pseudo.STATUS,"304",
		HTTP2Constants.Headers.Response.Pseudo.STATUS,"400",
		HTTP2Constants.Headers.Response.Pseudo.STATUS,"404",
		HTTP2Constants.Headers.Response.Pseudo.STATUS,"500",
		"accept-charset",null,
		"accept-encoding","gzip, deflate",
		"accept-language",null,
		"accept-ranges",null,
		"accept",null,
		"access-control-allow-origin",null,
		"age",null,
		"allow",null,
		"authorization",null,
		"cache-control",null,
		"content-disposition",null,
		"content-encoding",null,
		"content-language",null,
		"content-length",null,
		"content-location",null,
		"content-range",null,
		"content-type",null,
		"cookie",null,
		"date",null,
		"etag",null,
		"expect",null,
		"expires",null,
		"from",null,
		"host",null,
		"if-match",null,
		"if-modified-since",null,
		"if-none-match",null,
		"if-range",null,
		"if-unmodified-since",null,
		"last-modified",null,
		"link",null,
		"location",null,
		"max-forwards",null,
		"proxy-authenticate",null,
		"proxy-authorization",null,
		"range",null,
		"referer",null,
		"refresh",null,
		"retry-after",null,
		"server",null,
		"set-cookie",null,
		"strict-transport-security",null,
		"transfer-encoding",null,
		"user-agent",null,
		"vary",null,
		"via",null,
		"www-authenticate",null
	};
	public static final int STATIC_TABLE_SIZE = STATIC_TABLE.length / 2;

	protected String getName(int index) {
		if (index <= STATIC_TABLE_SIZE)
			return STATIC_TABLE[(index - 1) * 2];
		return dynTable.table.get((index - STATIC_TABLE_SIZE - 1) * 2);
	}
	
	/**
	 * Search name and value in index.
	 * The first index returned is the first index containing the name.
	 * The second index is the index containing both name and value.
	 * null is returned when not found.
	 */
	protected Pair<Integer, Integer> search(String name, String value) {
		Pair<Integer, Integer> result = new Pair<>(null, null);
		for (int i = 0; i < STATIC_TABLE_SIZE; ++i) {
			if (name.equals(STATIC_TABLE[i * 2])) {
				if (result.getValue1() == null)
					result.setValue1(Integer.valueOf(i + 1));
				if (value.equals(STATIC_TABLE[i * 2 + 1])) {
					result.setValue2(Integer.valueOf(i + 1));
					return result;
				}
			}
		}
		int index = STATIC_TABLE_SIZE + 1;
		for (Iterator<String> it = dynTable.table.iterator(); it.hasNext(); index++) {
			String n = it.next();
			String v = it.next();
			if (name.equals(n)) {
				if (result.getValue1() == null)
					result.setValue1(Integer.valueOf(index));
				if (value.equals(v)) {
					result.setValue2(Integer.valueOf(index));
					return result;
				}
			}
		}
		return result;
	}
	
	protected void addIndexedHeaderField(int index, HTTP2PseudoHeaderHandler headerHandler) throws HTTP2Error {
		if (index == 0)
			throw new HTTP2Error(true, HTTP2Error.Codes.COMPRESSION_ERROR, "Index 0 is not allowed");
		if (index <= STATIC_TABLE_SIZE) {
			int i = (index - 1) * 2;
			headerHandler.accept(STATIC_TABLE[i], STATIC_TABLE[i + 1]);
		} else {
			int i = (index - STATIC_TABLE_SIZE - 1) * 2;
			ListIterator<String> it = dynTable.table.listIterator(i);
			headerHandler.accept(it.next(), it.next());
		}
	}
	
}
