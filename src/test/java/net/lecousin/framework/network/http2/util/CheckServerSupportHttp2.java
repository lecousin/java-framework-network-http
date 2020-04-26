package net.lecousin.framework.network.http2.util;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

public class CheckServerSupportHttp2 {

	public static void main(String[] args) {
		String[] hosts = new String[] {
			"validator.w3.org",
			"link.springer.com",
			"marketplace.atlassian.com",
			"wikipedia.org",
			"http2.golang.org"
		};
		for (String host : hosts) {
			try {
				checkPrioriKnownledge(host, true);
				checkPrioriKnownledge(host, false);
				checkUpgrade(host, false, "h2c");
				checkUpgrade(host, true, "h2c");
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	public static void checkPrioriKnownledge(String host, boolean useSSL) throws Exception {
		Socket s;
		if (useSSL)
			s = SSLSocketFactory.getDefault().createSocket(InetAddress.getByName(host), 443);
		else
			s = SocketFactory.getDefault().createSocket(InetAddress.getByName(host), 80);
		s.setSoTimeout(10000);
		s.getOutputStream().write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
		InputStream in = s.getInputStream();
		byte[] buffer = new byte[8192];
		int done = 0;
		do {
			int nb;
			try { nb = in.read(buffer, done, buffer.length - done); }
			catch (SocketTimeoutException e) { break; }
			if (nb <= 0) break;
			done += nb;
		} while (done < 9);
		s.close();
		boolean ok = done >= 9 &&
			buffer[3] == 4 &&
			buffer[4] == 0 &&
			buffer[5] == 0 &&
			buffer[6] == 0 &&
			buffer[7] == 0 &&
			buffer[8] == 0;
		System.out.println(host + ":" + (useSSL ? 443 : 80) + " HTTP/2 PRI: " + ok);
	}
	
	public static void checkUpgrade(String host, boolean useSSL, String protocol) throws Exception {
		Socket s;
		if (useSSL)
			s = SSLSocketFactory.getDefault().createSocket(InetAddress.getByName(host), 443);
		else
			s = SocketFactory.getDefault().createSocket(InetAddress.getByName(host), 80);
		s.setSoTimeout(10000);
		s.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: Upgrade, HTTP2-Settings\r\nUpgrade: " + protocol + "\r\nHTTP2-Settings:\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		InputStream in = s.getInputStream();
		byte[] buffer = new byte[8192];
		int done = 0;
		do {
			int nb;
			try { nb = in.read(buffer, done, buffer.length - done); }
			catch (SocketTimeoutException e) { break; }
			if (nb <= 0) break;
			done += nb;
		} while (done < 13);
		s.close();
		boolean ok = done > 13 &&
			buffer[0] == 'H' &&
			buffer[1] == 'T' &&
			buffer[2] == 'T' &&
			buffer[3] == 'P' &&
			buffer[4] == '/' &&
			buffer[5] == '1' &&
			buffer[6] == '.' &&
			buffer[7] == '1' &&
			buffer[8] == ' ' &&
			buffer[9] == '1' &&
			buffer[10] == '0' &&
			buffer[11] == '1' &&
			buffer[12] == ' ';
		System.out.println(host + ":" + (useSSL ? 443 : 80) + ": upgrade to " + protocol + ": " + ok);
	}
	
}
