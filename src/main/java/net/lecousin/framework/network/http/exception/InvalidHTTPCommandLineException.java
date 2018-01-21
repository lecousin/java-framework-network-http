package net.lecousin.framework.network.http.exception;

import net.lecousin.framework.locale.LocalizableString;
import net.lecousin.framework.locale.LocalizableStringBuffer;

/** Invalid command line in the HTTP request. */
public class InvalidHTTPCommandLineException extends HTTPException {

	private static final long serialVersionUID = 2202038535038941643L;

	/** Constructor. */
	public InvalidHTTPCommandLineException(String commandLine) {
		super(
			new LocalizableStringBuffer(
				new LocalizableString("lc.http", "Invalid HTTP command line"),
				": ",
				commandLine
			)
		);
	}
	
}
