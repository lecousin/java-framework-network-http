package net.lecousin.framework.network.http.exception;

import net.lecousin.framework.locale.LocalizableString;
import net.lecousin.framework.locale.LocalizableStringBuffer;

/** Unknown HTTP protocol. */
public class UnsupportedHTTPProtocolException extends HTTPException {

	private static final long serialVersionUID = 5348642954474772180L;

	/** Constructor. */
	public UnsupportedHTTPProtocolException(String protocol) {
		super(new LocalizableStringBuffer(
			new LocalizableString("lc.http", "Invalid HTTP Protocol"),
			": ",
			protocol
		));
	}
	
}
