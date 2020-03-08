package net.lecousin.framework.network.http2;

/** HTTP/2 constants. */
public class HTTP2Constants {

	/** HTTP/2 specific headers. */
	public static class Headers {
		
		/** HTTP/2 request specific headers. */
		public static class Request extends Headers {
			
			public static final String HTTP2_SETTINGS = "HTTP2-Settings";

			/** HTTP/2 request pseudo headers. */
			public static class Pseudo extends Request {
				
				public static final String AUTHORITY	= ":authority";
				public static final String METHOD		= ":method";
				public static final String PATH			= ":path";
				public static final String SCHEME		= ":scheme";
				
			}
			
		}
		
		/** HTTP/2 response specific headers. */
		public static class Response extends Headers {
			
			/** HTTP/2 response pseudo headers. */
			public static class Pseudo extends Response {
				
				public static final String STATUS		= ":status";
				
			}
			
		}
		
	}
	
}
