package net.lecousin.framework.network.http.header;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import net.lecousin.framework.encoding.URLEncoding;
import net.lecousin.framework.io.data.BytesFromIso8859String;
import net.lecousin.framework.io.data.CharsFromString;
import net.lecousin.framework.network.mime.MimeException;
import net.lecousin.framework.network.mime.MimeUtil;
import net.lecousin.framework.network.mime.header.HeaderValueFormat;
import net.lecousin.framework.network.mime.header.parser.SpecialCharacter;
import net.lecousin.framework.network.mime.header.parser.Token;
import net.lecousin.framework.network.mime.header.parser.Word;
import net.lecousin.framework.text.ByteArrayStringIso8859Buffer;
import net.lecousin.framework.text.CharArrayString;

public class AlternativeService implements HeaderValueFormat {
	
	public static final String HEADER = "Alt-Svc";

	private String protocolId;
	private String hostname;
	private int port;
	private long maxAge = -1;
	
	public AlternativeService() {
		// nothing
	}
	
	public AlternativeService(String protocolId, String hostname, int port, long maxAge) {
		this.protocolId = protocolId;
		this.hostname = hostname;
		this.port = port;
		this.maxAge = maxAge;
	}
	
	public boolean isSame(AlternativeService s) {
		return protocolId.equals(s.protocolId) &&
			Objects.equals(hostname, s.hostname) &&
			port == s.port;
	}
	
	@Override
	public void parseTokens(List<Token> tokens) throws MimeException {
		List<List<Token>> params = Token.splitBySpecialCharacter(tokens, ';');
		for (List<Token> param : params) {
			Token.trim(param);
			Token.removeComments(param);
			CharArrayString s = new CharArrayString(Token.textLength(param));
			Token.asText(param, s);
			int i = s.indexOf('=');
			try {
				if (i >= 0) {
					String name = s.substring(0, i).trim().asString();
					String value = MimeUtil.decodeRFC2047(s.substring(i + 1).asString());
					parseParameter(name, value);
				} else {
					if (protocolId == null)
						throw new MimeException("Missing prototol in Alt-Svc: " + s);
				}
			} catch (Exception e) {
				throw new MimeException("Error decoding RFC2047 value", e);
			}
		}
	}
	
	private void parseParameter(String name, String value) throws MimeException {
		if (protocolId == null) {
			protocolId = URLEncoding.decode(new BytesFromIso8859String(name)).asString();
			int i = value.indexOf(':');
			if (i < 0) {
				hostname = value;
				port = 0;
			} else {
				hostname = value.substring(0, i);
				try {
					port = Integer.parseInt(value.substring(i + 1));
				} catch (NumberFormatException e) {
					throw new MimeException("Invalid Alt-Svc port number: " + value);
				}
			}
			if (hostname.length() == 0)
				hostname = null;
		} else {
			if ("ma".equals(name)) {
				try {
					maxAge = Long.parseLong(value);
				} catch (NumberFormatException e) {
					throw new MimeException("Invalid Alt-Svc max age: " + value);
				}
			}
		}
	}
	
	@Override
	public List<Token> generateTokens() {
		List<Token> tokens = new LinkedList<>();
		ByteArrayStringIso8859Buffer bytes = new ByteArrayStringIso8859Buffer();
		URLEncoding.encode(new CharsFromString(protocolId), bytes.asWritableBytes(), false, true);
		if (hostname == null && port <= 0) {
			tokens.add(new Word(bytes.asString()));
		} else {
			bytes.append('=');
			bytes.append('"');
			if (hostname != null)
				bytes.append(hostname);
			if (port > 0)
				bytes.append(':').append(Integer.toString(port));
			bytes.append('"');
			tokens.add(new Word(bytes.asString()));
		}
		if (maxAge >= 0) {
			tokens.add(new SpecialCharacter(';'));
			tokens.add(new Word("ma=" + Long.toString(maxAge)));
		}
		return tokens;
	}

	public String getProtocolId() {
		return protocolId;
	}

	public void setProtocolId(String protocolId) {
		this.protocolId = protocolId;
	}

	public String getHostname() {
		return hostname;
	}

	public void setHostname(String hostname) {
		this.hostname = hostname;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public long getMaxAge() {
		return maxAge;
	}

	public void setMaxAge(long maxAge) {
		this.maxAge = maxAge;
	}
	
	@Override
	public String toString() {
		return HEADER + ": " + Token.toString(generateTokens());
	}
}
