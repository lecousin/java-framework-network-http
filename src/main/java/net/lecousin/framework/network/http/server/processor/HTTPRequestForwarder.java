package net.lecousin.framework.network.http.server.processor;

import java.io.IOException;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.out2in.OutputToInputBuffers;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.mime.MimeHeader;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.transfer.encoding.ContentDecoderFactory;
import net.lecousin.framework.util.Pair;

/**
 * Forwards HTTP requests to another server, can be typically used by an HTTP proxy.
 */
public class HTTPRequestForwarder {
	
	/** Constructor. */
	public HTTPRequestForwarder(Logger logger, HTTPClientConfiguration clientConfig) {
		this.logger = logger;
		this.clientConfig = clientConfig;
		// TODO review how to reuse clients
		//new CleanOpenClients().executeEvery(90000, 120000).start();
	}

	protected Logger logger;
	protected HTTPClientConfiguration clientConfig;
	//protected Map<Pair<String,Integer>, LinkedList<HTTPClient>> openClients = new HashMap<>();
	
	public void setClientConfiguration(HTTPClientConfiguration config) {
		clientConfig = config;
	}
	
	/** Forward the request to the given host and port. */
	public IAsync<IOException> forward(HTTPRequest request, HTTPResponse response, String host, int port) {
		if (logger.debug())
			logger.debug("Forward request " + request.getPath() + " to " + host + ":" + port);
		prepareRequest(request, host, port, false);
		
		Async<IOException> result = new Async<>();
		Pair<String, Integer> address = new Pair<>(host, Integer.valueOf(port));
		HTTPClient client;
			/*
		synchronized (openClients) {
			LinkedList<HTTPClient> clients = openClients.get(address);
			if (clients == null)
				client = null;
			else {
				client = clients.removeFirst();
				while (client.getTCPClient().isClosed()) {
					if (clients.isEmpty()) {
						client = null;
						break;
					}
					client = clients.removeFirst();
				}
				if (clients.isEmpty())
					openClients.remove(address);
			}
		}
		if (client == null)*/
			client = new HTTPClient(new TCPClient(), host, port, clientConfig);
		/*else
			logger.debug("Reuse client to " + address);*/
		
		doForward(client, request, response, result, address);
		return result;
	}
	
	/** Forward the request to the given host and port. */
	public IAsync<IOException> forwardSSL(HTTPRequest request, HTTPResponse response, String host, int port) {
		prepareRequest(request, host, port, true);
		SSLClient ssl = new SSLClient(clientConfig.getSSLContext());
		ssl.setHostNames(host);
		HTTPClient client = new HTTPClient(ssl, host, port, clientConfig);
		Async<IOException> result = new Async<>();
		doForward(client, request, response, result, null);
		return result;
	}
	
	protected void prepareRequest(HTTPRequest request, String host, int port, boolean secure) {
		StringBuilder hostname = new StringBuilder(host);
		if (port != (secure ? HTTPClient.DEFAULT_HTTPS_PORT : HTTPClient.DEFAULT_HTTP_PORT))
			hostname.append(':').append(port);
		request.getMIME().setHeaderRaw("Host", hostname.toString());
		request.getMIME().setBodyToSend(request.getMIME().getBodyReceivedAsInput());
		StringBuilder s = new StringBuilder(128);
		for (String encoding : ContentDecoderFactory.getSupportedEncoding()) {
			if (s.length() > 0) s.append(", ");
			s.append(encoding);
		}
		request.getMIME().setHeaderRaw("Accept-Encoding", s.toString());
	}
	
	protected void doForward(
		HTTPClient client, HTTPRequest request, HTTPResponse response,
		Async<IOException> result, @SuppressWarnings({"unused","squid:S1172"}) Pair<String, Integer> reuseClientAddress
	) {
		client.sendRequest(request).onDone(() -> client.receiveResponseHeader().onDone(
			resp -> {
				response.setStatus(resp.getStatusCode());
				if (logger.trace()) {
					StringBuilder log = new StringBuilder(1024);
					log.append("Request ").append(request.getPath()).append(" returned headers:\r\n");
					for (MimeHeader h : resp.getMIME().getHeaders())
						log.append(h.getName()).append(':').append(h.getRawValue()).append("\r\n");
					logger.trace(log.toString());
				}
				for (MimeHeader h : resp.getMIME().getHeaders()) {
					String name = h.getNameLowerCase();
					if (MimeMessage.CONTENT_LENGTH.equalsIgnoreCase(name)) continue;
					if (MimeMessage.CONTENT_TRANSFER_ENCODING.equalsIgnoreCase(name)) continue;
					if (MimeMessage.CONTENT_ENCODING.equalsIgnoreCase(name)) continue;
					response.getMIME().addHeader(new MimeHeader(h.getName(), h.getRawValue()));
				}
				OutputToInputBuffers o2i = new OutputToInputBuffers(true, 8, Task.PRIORITY_NORMAL);
				response.getMIME().setBodyToSend(o2i);
				client.receiveBody(resp, o2i, 65536).onDone(() -> {
					o2i.endOfData();
						/*if (reuseClientAddress == null || client.getTCPClient().isClosed() ||
						"close".equalsIgnoreCase(request.getMIME().getFirstHeaderRawValue(HTTPRequest.HEADER_CONNECTION)))*/
						client.close();
						/*else synchronized (openClients) {
						LinkedList<HTTPClient> clients = openClients.get(reuseClientAddress);
						if (clients == null) {
							clients = new LinkedList<>();
							openClients.put(reuseClientAddress, clients);
						}
						clients.addLast(client);
					}*/
				}, error -> {
					logger.error("Error receiving body", error);
					o2i.signalErrorBeforeEndOfData(error);
					client.close();
				}, cancel -> {
					o2i.signalErrorBeforeEndOfData(IO.error(cancel));
					client.close();
				});
				result.unblock();
			}, result), result);
	}

	/*
	private class CleanOpenClients extends Task.Cpu<Void, NoException> {
		private CleanOpenClients() {
			super("Clean waiting HTTP clients", Task.PRIORITY_LOW);
		}
		
		@Override
		public Void run() {
			synchronized (openClients) {
				for (Iterator<Map.Entry<Pair<String, Integer>, LinkedList<HTTPClient>>> itEntry =
					openClients.entrySet().iterator(); itEntry.hasNext(); ) {
					Map.Entry<Pair<String, Integer>, LinkedList<HTTPClient>> e = itEntry.next();
					for (Iterator<HTTPClient> itClient = e.getValue().iterator(); itClient.hasNext(); ) {
						if (itClient.next().getTCPClient().isClosed())
							itClient.remove();
					}
					if (e.getValue().isEmpty())
						itEntry.remove();
				}
			}
			return null;
		}
	}*/

}
