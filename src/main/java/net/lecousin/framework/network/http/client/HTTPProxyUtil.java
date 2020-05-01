package net.lecousin.framework.network.http.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration.Protocol;
import net.lecousin.framework.network.http.client.HTTPClientConnection.OpenConnection;
import net.lecousin.framework.network.http.exception.HTTPResponseError;
import net.lecousin.framework.network.http1.HTTP1RequestCommandProducer;
import net.lecousin.framework.network.http1.HTTP1ResponseStatusConsumer;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.ssl.SSLConnectionConfig;
import net.lecousin.framework.text.ByteArrayStringIso8859Buffer;

/** Utility method to connect through a HTTP proxy. */
public final class HTTPProxyUtil {
	
	private HTTPProxyUtil() {
		// no instance
	}
	
	/** Open a tunnel on HTTP proxy server. */
	public static OpenConnection openTunnelThroughHTTPProxy(
		InetSocketAddress proxyAddress,
		String targetHostname, int targetPort,
		HTTPClientConfiguration config, SSLConnectionConfig sslConfig,
		Logger logger
	) {
		if (logger.debug())
			logger.debug("Open connection to HTTP" + (sslConfig != null ? "S" : "") + " server " + targetHostname + ":" + targetPort
				+ " through proxy " + proxyAddress);
		
		HTTPClientConfiguration proxyConfig = new HTTPClientConfiguration(config);
		proxyConfig.setAllowedProtocols(Arrays.asList(Protocol.HTTP1, Protocol.HTTP1S));
		OpenConnection proxyConnection = HTTPClientConnection.openDirectClearConnection(proxyAddress, proxyConfig, logger);
		
		// prepare the CONNECT request
		String host = targetHostname + ":" + targetPort;
		HTTPRequest connectRequest = new HTTPRequest()
			.setMethod(HTTPRequest.METHOD_CONNECT).setEncodedPath(new ByteArrayStringIso8859Buffer(host));
		connectRequest.addHeader(HTTPConstants.Headers.Request.HOST, host);
		ByteArrayStringIso8859Buffer headers = new ByteArrayStringIso8859Buffer();
		headers.setNewArrayStringCapacity(512);
		HTTP1RequestCommandProducer.generate(connectRequest, null, headers);
		headers.append("\r\n");
		connectRequest.getHeaders().generateString(headers);
		ByteBuffer[] buffers = headers.asByteBuffers();
		
		// prepare SSL client
		TCPClient client;
		if (sslConfig != null) {
			@SuppressWarnings("resource")
			SSLClient sslClient = new SSLClient(sslConfig);
			client = sslClient;
		} else {
			client = proxyConnection.getClient();
		}
		
		Async<IOException> connect = new Async<>();
		
		proxyConnection.getConnect().onDone(() ->
		proxyConnection.getClient().asConsumer(2, config.getTimeouts().getSend()).push(Arrays.asList(buffers)).onDone(() -> {
			// once headers are sent, receive the response status line
			HTTPResponse response = new HTTPResponse();
			proxyConnection.getClient().getReceiver().consume(
				new HTTP1ResponseStatusConsumer(response)
				.convert(ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), IO::error),
				4096, config.getTimeouts().getReceive())
			.onDone(() -> {
				// status line received
				if (!response.isSuccess()) {
					connect.error(new HTTPResponseError(response));
					return;
				}
				// read headers
				MimeHeaders responseHeaders = new MimeHeaders();
				response.setHeaders(responseHeaders);
				proxyConnection.getClient().getReceiver().consume(
					responseHeaders.createConsumer(config.getLimits().getHeadersLength())
					.convert(ByteArray::fromByteBuffer, (bytes, buffer) -> ((ByteArray)bytes).setPosition(buffer), IO::error),
					4096, config.getTimeouts().getReceive())
				.onDone(() -> {
					// headers received
					// tunnel connection established
					if (sslConfig != null) {
						// replace connection of SSLClient by the tunnel
						if (logger.debug())
							logger.debug("Tunnel open through proxy " + proxyAddress + ", start SSL handshake");
						((SSLClient)client).tunnelConnected(proxyConnection.getClient(), connect,
							config.getTimeouts().getReceive());
					} else {
						connect.unblock();
					}
				}, connect);
			}, connect);
		}, connect), connect);
		return new OpenConnection(client, connect, true);
	}
	
}
