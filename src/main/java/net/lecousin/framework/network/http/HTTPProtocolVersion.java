package net.lecousin.framework.network.http;

import net.lecousin.framework.text.CharArrayString;
import net.lecousin.framework.text.IString;

/** HTTP protocol version in format "HTTP/" DIGIT "." DIGIT */
public final class HTTPProtocolVersion {

	public static final String HTTP_VERSION_START = "HTTP/";
	
	/** Constructor. */
	public HTTPProtocolVersion(byte major, byte minor) {
		this.major = major;
		this.minor = minor;
	}
	
	private byte major;
	private byte minor;
	
	public byte getMajor() {
		return major;
	}
	
	public byte getMinor() {
		return minor;
	}

	/** Append the string HTTP/x.y */
	public void toString(IString s) {
		s.append(HTTP_VERSION_START).append((char)('0' + major)).append('.').append((char)('0' + minor));
	}
	
	@Override
	public String toString() {
		CharArrayString s = new CharArrayString(8);
		toString(s);
		return s.toString();
	}
	
	/** Parse a string HTTP/x.y */
	public static HTTPProtocolVersion parse(IString s) {
		if (s.length() != 8) return null;
		if (!s.startsWith(HTTP_VERSION_START)) return null;
		if (s.charAt(6) != '.') return null;
		
		char digit = s.charAt(5);
		if (digit < '0' || digit > '9') return null;
		byte major = (byte)(digit - '0');
		
		digit = s.charAt(7);
		if (digit < '0' || digit > '9') return null;
		byte minor = (byte)(digit - '0');
		
		return new HTTPProtocolVersion(major, minor);
	}
	
}
