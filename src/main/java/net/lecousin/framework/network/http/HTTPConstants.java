package net.lecousin.framework.network.http;

/** Constants used for HTTP protocol. */
public final class HTTPConstants {

	private HTTPConstants() {
		// no instance
	}
	
	public static final int DEFAULT_HTTP_PORT = 80;
	public static final int DEFAULT_HTTPS_PORT = 443;
	
	public static final String HTTP_SCHEME = "http";
	public static final String HTTPS_SCHEME = "https";
	
	/** Constants for HTTP headers. */
	public static class Headers {
		
		private Headers() { /* no instance */ }

		public static final String CONNECTION = "Connection";

		public static final String CONNECTION_VALUE_KEEP_ALIVE = "Keep-Alive";
		public static final String CONNECTION_VALUE_CLOSE = "Close";
		public static final String CONNECTION_VALUE_UPGRADE = "Upgrade";

		/** Constants for HTTP headers specific to clients. */
		public static class Request extends Headers {

			private Request() { /* no instance */ }

			public static final String HOST = "Host";
			public static final String RANGE = "Range";
			public static final String UPGRADE = "Upgrade";
			public static final String USER_AGENT = "User-Agent";

			public static final String DEFAULT_USER_AGENT = "net.lecousin.framework.network.http.client/" + LibraryVersion.VERSION;
			
		}
		
		/** Constants for HTTP headers specific to servers. */
		public static class Response extends Headers {

			private Response() { /* no instance */ }
			
			public static final String ACCEPT_RANGES = "Accept-Ranges";
			public static final String CACHE_CONTROL = "Cache-Control";
			public static final String CONTENT_RANGE = "Content-Range";
			public static final String EXPIRES = "Expires";
			public static final String LOCATION = "Location";
			public static final String PRAGMA = "Pragma";
			public static final String SERVER = "Server";
			
			public static final String ACCEPT_RANGES_VALUE_BYTES = "bytes";
			
			public static final String DEFAULT_SERVER = "net.lecousin.framework.network.http.server/" + LibraryVersion.VERSION;

		}
		
	}

}
