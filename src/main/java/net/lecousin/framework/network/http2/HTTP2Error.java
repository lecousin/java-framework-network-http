package net.lecousin.framework.network.http2;

/** HTTP/2 protocol error. */
public class HTTP2Error extends Exception {

	private static final long serialVersionUID = 1L;
	
	protected final boolean connectionError;
	protected final int errorCode;
	
	/** Constructor. */
	public HTTP2Error(boolean connectionError, int errorCode, String message) {
		super(message);
		this.connectionError = connectionError;
		this.errorCode = errorCode;
	}

	/** Constructor. */
	public HTTP2Error(boolean connectionError, int errorCode) {
		this(connectionError, errorCode, null);
	}
	
	/** Return true if this is a connection error, false for a stream error. */
	public boolean isConnectionError() {
		return connectionError;
	}
	
	public int getErrorCode() {
		return errorCode;
	}
	
	/** HTTP/2 error codes defined by specification. */
	public static final class Codes {
		
		private Codes() {
			/* no instance */
		}
		
		public static final int NO_ERROR				= 0x00000000;
		public static final int PROTOCOL_ERROR			= 0x00000001;
		public static final int INTERNAL_ERROR			= 0x00000002;
		public static final int FLOW_CONTROL_ERROR		= 0x00000003;
		public static final int SETTINGS_TIMEOUT		= 0x00000004;
		public static final int STREAM_CLOSED			= 0x00000005;
		public static final int FRAME_SIZE_ERROR		= 0x00000006;
		public static final int REFUSED_STREAM			= 0x00000007;
		public static final int CANCEL					= 0x00000008;
		public static final int COMPRESSION_ERROR		= 0x00000009;
		public static final int CONNECT_ERROR			= 0x0000000A;
		public static final int ENHANCE_YOUR_CALM		= 0x0000000B;
		public static final int INADEQUATE_SECURITY		= 0x0000000C;
		public static final int HTTP_1_1_REQUIRED		= 0x0000000D;
		
	}
	
}
