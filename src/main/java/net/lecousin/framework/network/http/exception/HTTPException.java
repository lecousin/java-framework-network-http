package net.lecousin.framework.network.http.exception;

import net.lecousin.framework.exception.LocalizableException;
import net.lecousin.framework.locale.ILocalizableString;

/** HTTP exception. */
public abstract class HTTPException extends LocalizableException {

	private static final long serialVersionUID = 912733618674412618L;
	
	/** Constructor. */
	public HTTPException(ILocalizableString string, Throwable cause) {
		super(string, cause);
	}
	
	/** Constructor. */
	public HTTPException(ILocalizableString string) {
		super(string);
	}
	
	/** Constructor. */
	public HTTPException(String namespace, String string, Object... values) {
		super(namespace, string, values);
	}
	
	/** Constructor. */
	public HTTPException(String message, Throwable cause) {
		super(message, cause);
	}
	
	/** Constructor. */
	public HTTPException(String message) {
		super(message);
	}

	
}
