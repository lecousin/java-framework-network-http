package net.lecousin.framework.network.http2.connection;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

import net.lecousin.framework.network.http2.frame.HTTP2Ping;
import net.lecousin.framework.util.Triple;

class Pinger {

	private HTTP2Connection conn;
	private List<Triple<byte[], Consumer<Boolean>, Long>> pingsSent = new LinkedList<>();
	
	Pinger(HTTP2Connection conn) {
		this.conn = conn;
	}

	void pingResponse(byte[] data) {
		Consumer<Boolean> listener = null;
		synchronized (pingsSent) {
			for (Iterator<Triple<byte[], Consumer<Boolean>, Long>> it = pingsSent.iterator(); it.hasNext(); ) {
				Triple<byte[], Consumer<Boolean>, Long> p = it.next();
				if (Arrays.equals(data, p.getValue1())) {
					listener = p.getValue2();
					it.remove();
					break;
				}
			}
		}
		if (listener != null)
			listener.accept(Boolean.TRUE);
	}

	void sendPing(byte[] data, Consumer<Boolean> onResponseReceived, long timeout) {
		synchronized (pingsSent) {
			pingsSent.add(new Triple<>(data, onResponseReceived, Long.valueOf(timeout)));
		}
		conn.sendFrame(new HTTP2Ping.Writer(data, false), false, false);
		// TODO manage timeout
	}

	
}
