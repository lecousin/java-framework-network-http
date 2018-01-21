package net.lecousin.framework.network.http.exception;

import net.lecousin.framework.locale.LocalizableString;
import net.lecousin.framework.locale.LocalizableStringBuffer;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.util.StringUtil;

/** Invalid HTTP method. */
public class InvalidHTTPMethodException extends HTTPException {

	private static final long serialVersionUID = 4423907049540242622L;

	/** Constructor. */
	public InvalidHTTPMethodException(String method) {
		super(
			new LocalizableStringBuffer(
				new LocalizableString("lc.http", "Invalid HTTP method"),
				": ",method,", ",
				new LocalizableString("b", "possible values are"),
				" ",
				StringUtil.possibleValues(HTTPRequest.Method.class)
			)
		);
	}
	
}
