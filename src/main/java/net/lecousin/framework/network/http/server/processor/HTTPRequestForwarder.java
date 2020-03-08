package net.lecousin.framework.network.http.server.processor;

import java.io.IOException;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.out2in.OutputToInputBuffers;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.HTTPConstants;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.http.client.HTTPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.http.server.HTTPServerResponse;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.transfer.ContentDecoderFactory;
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
	public void forward(HTTPRequestContext ctx, String host, int port) {
		if (logger.debug())
			logger.debug("Forward request " + ctx.getRequest().getDecodedPath() + " to " + host + ":" + port);
		prepareRequest(ctx, host, port, false);
		
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
		
		doForward(client, ctx, address);
	}
	
	/** Forward the request to the given host and port. */
	public void forwardSSL(HTTPRequestContext ctx, String host, int port) {
		prepareRequest(ctx, host, port, true);
		SSLClient ssl = new SSLClient(clientConfig.getSSLContext());
		ssl.setHostNames(host);
		HTTPClient client = new HTTPClient(ssl, host, port, clientConfig);
		doForward(client, ctx, null);
	}
	
	protected void prepareRequest(HTTPRequestContext ctx, String host, int port, boolean secure) {
		StringBuilder hostname = new StringBuilder(host);
		if (port != (secure ? HTTPConstants.DEFAULT_HTTPS_PORT : HTTPConstants.DEFAULT_HTTP_PORT))
			hostname.append(':').append(port);
		HTTPRequest request = ctx.getRequest();
		request.setHeader("Host", hostname.toString());
		StringBuilder s = new StringBuilder(128);
		for (String encoding : ContentDecoderFactory.getSupportedEncoding()) {
			if (s.length() > 0) s.append(", ");
			s.append(encoding);
		}
		request.setHeader("Accept-Encoding", s.toString());
	}
	
	protected void doForward(
		HTTPClient client, HTTPRequestContext ctx,
		@SuppressWarnings({"unused","squid:S1172"}) Pair<String, Integer> reuseClientAddress
	) {
		HTTPServerResponse response = ctx.getResponse();
		AsyncSupplier<HTTPResponse, IOException> forward = client.sendAndReceive(ctx.getRequest(), resp -> {
			response.setStatus(resp.getStatusCode());
			if (logger.trace()) {
				StringBuilder log = new StringBuilder(1024);
				log.append("Request ").append(ctx.getRequest().getDecodedPath()).append(" returned headers:\r\n");
				for (MimeHeader h : resp.getHeaders().getHeaders())
					log.append(h.getName()).append(':').append(h.getRawValue()).append("\r\n");
				logger.trace(log.toString());
			}
			for (MimeHeader h : resp.getHeaders().getHeaders()) {
				String name = h.getNameLowerCase();
				if (MimeHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) continue;
				if (MimeHeaders.CONTENT_TRANSFER_ENCODING.equalsIgnoreCase(name)) continue;
				if (MimeHeaders.CONTENT_ENCODING.equalsIgnoreCase(name)) continue;
				response.addHeader(new MimeHeader(h.getName(), h.getRawValue()));
			}
			return null;
		}, (parent, headers) -> {
			// TODO if size is known, we should be able to send it
			BinaryEntity entity = new BinaryEntity(parent, headers);
			OutputToInputBuffers o2i = new OutputToInputBuffers(true, 8, Task.Priority.NORMAL);
			entity.setContent(o2i);
			BinaryEntity respEntity = new BinaryEntity(null, response.getHeaders());
			respEntity.setContent(o2i);
			response.setEntity(respEntity);
			response.getReady().unblock();
			return entity;
		});
		forward.onError(error -> {
			response.getReady().error(error);
			if (response.getEntity() != null) {
				((IO.OutputToInput)((BinaryEntity)response.getEntity()).getContent()).signalErrorBeforeEndOfData(error);
			}
		});
		forward.onDone(client::close);
		/*
			if (reuseClientAddress == null || client.getTCPClient().isClosed() ||
			"close".equalsIgnoreCase(request.getMIME().getFirstHeaderRawValue(HTTPRequest.HEADER_CONNECTION)))
			//client.close();
			else synchronized (openClients) {
			LinkedList<HTTPClient> clients = openClients.get(reuseClientAddress);
			if (clients == null) {
				clients = new LinkedList<>();
				openClients.put(reuseClientAddress, clients);
			}
			clients.addLast(client);
			*/
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
