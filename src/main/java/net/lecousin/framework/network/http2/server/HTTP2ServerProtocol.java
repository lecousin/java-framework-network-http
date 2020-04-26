package net.lecousin.framework.network.http2.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.List;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.encoding.Base64Encoding;
import net.lecousin.framework.io.data.BytesFromIso8859String;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.exception.HTTPException;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;
import net.lecousin.framework.network.http.server.HTTPServerResponse;
import net.lecousin.framework.network.http.server.errorhandler.DefaultErrorHandler;
import net.lecousin.framework.network.http.server.errorhandler.HTTPErrorHandler;
import net.lecousin.framework.network.http1.server.HTTP1ServerProtocol;
import net.lecousin.framework.network.http1.server.HTTP1ServerUpgradeProtocol;
import net.lecousin.framework.network.http2.HTTP2Constants;
import net.lecousin.framework.network.http2.frame.HTTP2Settings;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.ALPNServerProtocol;

/**
 * HTTP/2 Server protocol.
 * <a href="https://tools.ietf.org/html/rfc7540">Specification</a>
 */
public class HTTP2ServerProtocol implements HTTP1ServerUpgradeProtocol, ALPNServerProtocol {

	/** Default constructor. */
	public HTTP2ServerProtocol(HTTPRequestProcessor processor) {
		this.processor = processor;
		this.errorHandler = DefaultErrorHandler.getInstance();
	}
	
	/** Inherits configuration from HTTP/1 protocol and declares HTTP/2 as upgradable protocol. */
	public HTTP2ServerProtocol(HTTP1ServerProtocol httpProtocol) {
		this.processor = httpProtocol.getProcessor();
		this.receiveDataTimeout = httpProtocol.getReceiveDataTimeout();
		this.sendDataTimeout = httpProtocol.getSendDataTimeout();
		this.errorHandler = httpProtocol.getErrorHandler();
		httpProtocol.addUpgradeProtocol(this);
	}
	
	Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(HTTP2ServerProtocol.class);
	ByteArrayCache bufferCache = ByteArrayCache.getInstance();
	private HTTPRequestProcessor processor;
	private int receiveDataTimeout = 0;
	private int sendDataTimeout = 0;
	private HTTPErrorHandler errorHandler;
	private HTTP2Settings settings = new HTTP2Settings()
		.setWindowSize(128L * 1024)
		.setHeaderTableSize(4096) // it is supposed to be good to keep indexes on 7-bits
		.setMaxConcurrentStreams(10) // limit pending requests for a client
		.setEnablePush(false); // we do not do that for now
	private boolean enableRangeRequests = false;
	
	@Override
	public String getALPNName() {
		return "h2";
	}
	
	@Override
	public String toString() {
		return "HTTP/2 protocol using processor " + processor;
	}
	
	public HTTPRequestProcessor getProcessor() {
		return processor;
	}

	public HTTPErrorHandler getErrorHandler() {
		return errorHandler;
	}

	public void setErrorHandler(HTTPErrorHandler errorHandler) {
		this.errorHandler = errorHandler;
	}

	public int getReceiveDataTimeout() {
		return receiveDataTimeout;
	}
	
	public void setReceiveDataTimeout(int timeout) {
		receiveDataTimeout = timeout;
	}
	
	public int getSendDataTimeout() {
		return sendDataTimeout;
	}
	
	public void setSendDataTimeout(int timeout) {
		sendDataTimeout = timeout;
	}
	
	public HTTP2Settings getSettings() {
		return settings;
	}

	public boolean rangeRequestsEnabled() {
		return enableRangeRequests;
	}

	public void enableRangeRequests(boolean enableRangeRequests) {
		this.enableRangeRequests = enableRangeRequests;
	}

	@Override
	public String getUpgradeProtocolToken() {
		return "h2c";
	}
	
	@Override
	public boolean acceptUpgrade(TCPServerClient client, HTTPRequest request) {
		List<MimeHeader> settingsHeaders = request.getHeaders().getList(HTTP2Constants.Headers.Request.HTTP2_SETTINGS);
		if (settingsHeaders.size() != 1)
			return false;
		try {
			byte[] bytes = Base64Encoding.instanceURL.decode(new BytesFromIso8859String(settingsHeaders.get(0).getRawValue()));
			HTTP2Settings.Reader clientSettings = new HTTP2Settings.Reader(bytes.length);
			clientSettings.createConsumer().consume(ByteBuffer.wrap(bytes));
			client.setAttribute(ATTRIBUTE_SETTINGS_FROM_UPGRADE, clientSettings);
			if (logger.trace())
				logger.trace("Upgrade to HTTP/2 accepted for " + client);
		} catch (Exception e) {
			return false;
		}
		return true;
	}
	
	@Override
	@SuppressWarnings("java:S1764") // data.get() does not always return the same value !
	public boolean isUpgradeRequest(TCPServerClient client, HTTPRequest request, ByteBuffer data) throws HTTPException {
		// HTTP/2.0 must be specified
		if (request.getProtocolVersion().getMajor() != 2)
			return false;
		if (request.getProtocolVersion().getMinor() != 0)
			return false;
		// command must be PRI
		if (!"PRI".equals(request.getMethod()))
			return false;
		// path must be *
		if (!"*".equals(request.getDecodedPath()))
			return false;
		// no header expected
		if (!request.getHeaders().getHeaders().isEmpty())
			return false;
		// SM\r\n\r\n expected
		if (data.remaining() < 6)
			return false;
		if (data.get() != 'S' ||
			data.get() != 'M' ||
			data.get() != '\r' ||
			data.get() != '\n' ||
			data.get() != '\r' ||
			data.get() != '\n')
			throw new HTTPException("HTTP/2 Preface must contain SM<CRLF><CRLF>");
		if (logger.trace())
			logger.trace("Direct upgrade to HTTP/2 accepted for " + client);
		return true;
	}

	@Override
	public int startProtocol(TCPServerClient client) {
		// check if we are using the upgrade mechanism
		if (client.hasAttribute(HTTP1ServerProtocol.UPGRADED_PROTOCOL_REQUEST_CONTEXT_ATTRIBUTE)) {
			HTTPRequestContext ctx = (HTTPRequestContext)client.getAttribute(
				HTTP1ServerProtocol.UPGRADED_PROTOCOL_REQUEST_CONTEXT_ATTRIBUTE);
			// respond with a 101 Switching Protocols
			HTTPServerResponse resp = ctx.getResponse();
			resp.setStatus(101, "Switching Protocols");
			resp.addHeader("Connection", "Upgrade");
			resp.addHeader("Upgrade", "h2c");
			resp.setForceNoContent(true);
			resp.getReady().unblock();
			HTTP2Settings clientSettings = (HTTP2Settings)client.removeAttribute(ATTRIBUTE_SETTINGS_FROM_UPGRADE);
			ClientStreamsManager manager = new ClientStreamsManager(this, client, clientSettings);
			client.setAttribute(ATTRIBUTE_CLIENT_STREAMS_MANAGER, manager);
		} else if (client.hasAttribute(HTTP1ServerProtocol.UPGRADED_PROTOCOL_ATTRIBUTE)) {
			// preface already received
			ClientStreamsManager manager = new ClientStreamsManager(this, client, null);
			client.setAttribute(ATTRIBUTE_CLIENT_STREAMS_MANAGER, manager);
		}
		
		return receiveDataTimeout;
	}

	@Override
	public int getInputBufferSize(TCPServerClient client) {
		return (int)Math.min(32768, settings.getMaxFrameSize());
	}

	private static final String ATTRIBUTE_SETTINGS_FROM_UPGRADE = "http2.server.upgrade.settings";
	private static final String ATTRIBUTE_CLIENT_STREAMS_MANAGER = "http2.server.streams-manager";
	private static final String ATTRIBUTE_CLIENT_PREFACE_POSITION = "http2.preface.position";
	private static final byte[] HTTP2_PREFACE = new byte[] {
		'P', 'R', 'I', ' ', '*', ' ', 'H', 'T', 'T', 'P', '/', '2', '.', '0', '\r', '\n',
		'\r', '\n',
		'S', 'M', '\r', '\n',
		'\r', '\n'
	};

	@Override
	public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data) {
		ClientStreamsManager manager = (ClientStreamsManager)client.getAttribute(ATTRIBUTE_CLIENT_STREAMS_MANAGER);
		if (manager == null) {
			// we expect the preface to come
			Integer posInt = (Integer)client.getAttribute(ATTRIBUTE_CLIENT_PREFACE_POSITION);
			int pos;
			if (posInt == null)
				pos = 0;
			else
				pos = posInt.intValue();
			while (data.hasRemaining() && pos < HTTP2_PREFACE.length) {
				byte b = data.get();
				if (b != HTTP2_PREFACE[pos++]) {
					logger.warn("Invalid client preface from " + client);
					client.close();
					return;
				}
			}
			if (pos < HTTP2_PREFACE.length) {
				client.setAttribute(ATTRIBUTE_CLIENT_PREFACE_POSITION, Integer.valueOf(pos));
			} else {
				client.removeAttribute(ATTRIBUTE_CLIENT_PREFACE_POSITION);
				manager = new ClientStreamsManager(this, client, null);
				client.setAttribute(ATTRIBUTE_CLIENT_STREAMS_MANAGER, manager);
			}
			if (!data.hasRemaining()) {
				bufferCache.free(data);
				try { client.waitForData(receiveDataTimeout); }
				catch (ClosedChannelException e) { /* ignore. */ }
				return;
			}
		}
		Async<IOException> consume = manager.consumeDataFromRemote(data);
		consume.onSuccess(() -> {
			bufferCache.free(data);
			try { client.waitForData(receiveDataTimeout); }
			catch (ClosedChannelException e) { /* ignore. */ }
		});
	}

}
