package net.lecousin.framework.network.http2.server;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import net.lecousin.framework.application.LCCore;
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
import net.lecousin.framework.network.http2.frame.HTTP2Frame;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.frame.HTTP2GoAway;
import net.lecousin.framework.network.http2.frame.HTTP2Settings;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.server.TCPServerClient;

/**
 * HTTP/2 Server protocol.
 * <a href="https://tools.ietf.org/html/rfc7540">Specification</a>
 */
public class HTTP2ServerProtocol implements HTTP1ServerUpgradeProtocol {

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
	private long initialWindowSize = 128L * 1024;
	private int compressionHeaderTableSize = 4096; // it is supposed to be good to keep indexes on 7-bits
	
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
	
	public long getInitialWindowSize() {
		return initialWindowSize;
	}
	
	public int getCompressionHeaderTableSize() {
		return compressionHeaderTableSize;
	}

	@Override
	public String getUpgradeProtocolToken() {
		return "h2c";
	}
	
	@Override
	public boolean acceptUpgrade(TCPServerClient client, HTTPRequest request) {
		List<MimeHeader> settings = request.getHeaders().getList(HTTP2Constants.Headers.Request.HTTP2_SETTINGS);
		if (settings.size() != 1)
			return false;
		try {
			byte[] bytes = Base64Encoding.instanceURL.decode(new BytesFromIso8859String(settings.get(0).getRawValue()));
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
		HTTP2Settings clientSettings;
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
			clientSettings = (HTTP2Settings)client.removeAttribute(ATTRIBUTE_SETTINGS_FROM_UPGRADE);
		} else {
			clientSettings = null;
		}
		client.setAttribute(ATTRIBUTE_FRAME_STATE, FrameState.START);
		ClientStreamsManager manager = new ClientStreamsManager(this, client, clientSettings);
		client.setAttribute(ATTRIBUTE_CLIENT_STREAMS_MANAGER, manager);
		
		// send server settings
		HTTP2Settings.Writer serverSettings = new HTTP2Settings.Writer(true);
		serverSettings.setEnablePush(false);
		serverSettings.setWindowSize(initialWindowSize);
		serverSettings.setHeaderTableSize(compressionHeaderTableSize);
		manager.sendFrame(serverSettings, false);
		
		if (clientSettings != null) {
			// send ACK
			manager.sendFrame(new HTTP2Frame.EmptyWriter(
				new HTTP2FrameHeader(0, HTTP2FrameHeader.TYPE_SETTINGS, HTTP2Settings.FLAG_ACK, 0)), false);
		}
		return receiveDataTimeout;
	}

	@Override
	public int getInputBufferSize() {
		return 8192;
	}

	static final String ATTRIBUTE_FRAME_STATE = "http2.frame.state";
	static final String ATTRIBUTE_FRAME_HEADER = "http2.frame.header";
	static final String ATTRIBUTE_FRAME_HEADER_CONSUMER = "http2.frame.header.consumer";
	static final String ATTRIBUTE_CLIENT_STREAMS_MANAGER = "http2.streams-manager";
	static final String ATTRIBUTE_FRAME_STREAM_HANDLER = "http2.stream-handler";
	private static final String ATTRIBUTE_SETTINGS_FROM_UPGRADE = "http2.upgrade.settings";
	
	private enum FrameState {
		START, HEADER, PAYLOAD
	}

	@Override
	public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data) {
		FrameState frameState = (FrameState)client.getAttribute(ATTRIBUTE_FRAME_STATE);
		switch (frameState) {
		case START:
			startFrame(client, data);
			break;
		case HEADER:
			continueFrameHeader(client, data);
			break;
		case PAYLOAD:
			continueFramePayload(client, data);
			break;
		default: // not possible
		}
	}
	
	private void startFrame(TCPServerClient client, ByteBuffer data) {
		if (logger.trace())
			logger.trace("Start receiving new frame from " + client);
		HTTP2FrameHeader header = new HTTP2FrameHeader();
		HTTP2FrameHeader.Consumer consumer = header.createConsumer();
		boolean headerReady = consumer.consume(data);
		if (!headerReady) {
			bufferCache.free(data);
			client.setAttribute(ATTRIBUTE_FRAME_STATE, FrameState.HEADER);
			client.setAttribute(ATTRIBUTE_FRAME_HEADER, header);
			client.setAttribute(ATTRIBUTE_FRAME_HEADER_CONSUMER, consumer);
			try { client.waitForData(receiveDataTimeout); }
			catch (ClosedChannelException e) { /* ignore. */ }
			return;
		}
		startFramePayload(client, data);
	}
	
	
	private void continueFrameHeader(TCPServerClient client, ByteBuffer data) {
		HTTP2FrameHeader.Consumer consumer = (HTTP2FrameHeader.Consumer)client.getAttribute(ATTRIBUTE_FRAME_HEADER_CONSUMER);
		boolean headerReady = consumer.consume(data);
		if (!headerReady) {
			bufferCache.free(data);
			try { client.waitForData(receiveDataTimeout); }
			catch (ClosedChannelException e) { /* ignore. */ }
			return;
		}
		startFramePayload(client, data);
	}

	private void startFramePayload(TCPServerClient client, ByteBuffer data) {
		client.removeAttribute(ATTRIBUTE_FRAME_HEADER_CONSUMER);
		client.setAttribute(ATTRIBUTE_FRAME_STATE, FrameState.PAYLOAD);
		ClientStreamsManager manager = (ClientStreamsManager)client.getAttribute(ATTRIBUTE_CLIENT_STREAMS_MANAGER);
		HTTP2FrameHeader header = (HTTP2FrameHeader)client.getAttribute(ATTRIBUTE_FRAME_HEADER);
		if (logger.trace())
			logger.trace("Frame header received with type " + header.getType() + ", flags " + header.getFlags()
				+ ", stream " + header.getStreamId() + ", payload length " + header.getPayloadLength());
		StreamHandler stream = manager.startFrame(header);
		if (stream == null)
			return; // connection error
		if (header.getPayloadLength() == 0) {
			endOfFrame(client, data);
			return;
		}
		if (!data.hasRemaining()) {
			bufferCache.free(data);
			try { client.waitForData(receiveDataTimeout); }
			catch (ClosedChannelException e) { /* ignore. */ }
			return;
		}
		stream.consumeFramePayload(manager, data);
	}	
	
	private static void continueFramePayload(TCPServerClient client, ByteBuffer data) {
		ClientStreamsManager manager = (ClientStreamsManager)client.getAttribute(ATTRIBUTE_CLIENT_STREAMS_MANAGER);
		StreamHandler stream = (StreamHandler)client.getAttribute(ATTRIBUTE_FRAME_STREAM_HANDLER);
		stream.consumeFramePayload(manager, data);
	}
	
	void endOfFrame(TCPServerClient client, ByteBuffer data) {
		client.setAttribute(ATTRIBUTE_FRAME_STATE, FrameState.START);
		client.removeAttribute(ATTRIBUTE_FRAME_HEADER);
		client.removeAttribute(ATTRIBUTE_FRAME_STREAM_HANDLER);
		if (!data.hasRemaining()) {
			bufferCache.free(data);
			try { client.waitForData(receiveDataTimeout); }
			catch (ClosedChannelException e) { /* ignore. */ }
			return;
		}
		// TODO check number of processed requests and delay receiving the next one if needed
		// TODO launch it in a new task to avoid recursivity
		dataReceivedFromClient(client, data);
	}

	static void connectionError(TCPServerClient client, int errorCode, String debugMessage) {
		ClientStreamsManager manager = (ClientStreamsManager)client.getAttribute(ATTRIBUTE_CLIENT_STREAMS_MANAGER);
		HTTP2GoAway.Writer frame = new HTTP2GoAway.Writer(manager.getLastStreamId(), errorCode,
			debugMessage != null ? debugMessage.getBytes(StandardCharsets.UTF_8) : null);
		manager.sendFrame(frame, true);
	}
	
}
