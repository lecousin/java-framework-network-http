package net.lecousin.framework.network.http2;

import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.HTTPProtocolVersion;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;

/** Handler for pseudo headers. */
public interface HTTP2PseudoHeaderHandler {

	/** Accept a pseudo header. */
	void accept(String name, String value) throws HTTP2Error;
	
	/** Pseudo header handler for an HTTPRequest. */
	public static class Request implements HTTP2PseudoHeaderHandler {
		
		private HTTPRequest request;
		
		/** Constructor. */
		public Request(HTTPRequest request) {
			this.request = request;
			request.setProtocolVersion(new HTTPProtocolVersion((byte)2, (byte)0));
		}

		@Override
		public void accept(String name, String value) throws HTTP2Error {
			switch (name) {
			case HTTP2Constants.Headers.Request.Pseudo.AUTHORITY:
				request.getHeaders().addRawValue(HTTPConstants.Headers.Request.HOST, value);
				break;
			case HTTP2Constants.Headers.Request.Pseudo.METHOD:
				request.setMethod(value);
				break;
			case HTTP2Constants.Headers.Request.Pseudo.PATH:
				request.setURI(value);
				break;
			case HTTP2Constants.Headers.Request.Pseudo.SCHEME:
				// TODO
				break;
			default:
				throw new HTTP2Error(0, HTTP2Error.Codes.PROTOCOL_ERROR, "Unexpected pseudo header " + name);
			}
		}
	}
	
	/** Pseudo header handler for an HTTPResponse. */
	public static class Response implements HTTP2PseudoHeaderHandler {
		
		private HTTPResponse response;
		
		/** Constructor. */
		public Response(HTTPResponse response) {
			this.response = response;
			response.setProtocolVersion(new HTTPProtocolVersion((byte)2, (byte)0));
		}
		
		@Override
		public void accept(String name, String value) throws HTTP2Error {
			switch (name) {
			case HTTP2Constants.Headers.Response.Pseudo.STATUS:
				try {
					response.setStatus(Integer.parseUnsignedInt(value));
				} catch (NumberFormatException e) {
					throw new HTTP2Error(0, HTTP2Error.Codes.PROTOCOL_ERROR, "Invalid status code " + value);
				}
				break;
			default:
				throw new HTTP2Error(0, HTTP2Error.Codes.PROTOCOL_ERROR, "Unexpected pseudo header " + name);
			}
		}
	}
	
	public static class IgnoreAll implements HTTP2PseudoHeaderHandler {
		@Override
		public void accept(String name, String value) {
			// ignore
		}
	}
	
}
