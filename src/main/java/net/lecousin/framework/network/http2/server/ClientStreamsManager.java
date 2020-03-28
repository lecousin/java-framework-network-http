package net.lecousin.framework.network.http2.server;

import net.lecousin.framework.network.http2.frame.HTTP2Settings;
import net.lecousin.framework.network.http2.streams.DataHandler;
import net.lecousin.framework.network.http2.streams.StreamsManager;
import net.lecousin.framework.network.server.TCPServerClient;

class ClientStreamsManager extends StreamsManager {
	
	// TODO may be we shoud limit the data received for each client in case of heavy load ?
	// we could delay the client.waitForData for clients having already things to process ?
	// this may be also relevent for HTTP/1 server...
	
	private HTTP2ServerProtocol server;
	
	ClientStreamsManager(HTTP2ServerProtocol server, TCPServerClient client, HTTP2Settings initialSettings) {
		super(client, false, server.getSettings(), initialSettings, server.getSendDataTimeout(), server.logger, server.bufferCache);
		this.server = server;
	}
	
	@Override
	public DataHandler createDataHandler(int streamId) {
		return new ClientDataHandler(this, (TCPServerClient)remote, streamId, server);
	}
	

}
