package net.lecousin.framework.network.http2.util;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

import net.lecousin.framework.util.DebugUtil;

public class CheckServerSupportHttp2 {

	public static void main(String[] args) {
		try {
			//String host = "validator.w3.org"; // HTTP/2 ok, upgrade to h2c ok (tls or not)
			//String host = "link.springer.com"; // HTTP/2 ok
			//String host = "marketplace.atlassian.com"; // HTTP/2 ok
			//String host = "wikipedia.org"; // HTTP/2 ok

			String host = "eu.httpbin.org";
			
			// https://http2.golang.org/reqinfo to test http/2

			System.out.println(" ---- HTTP/2.0 -----");
			
			Socket s = SSLSocketFactory.getDefault().createSocket(InetAddress.getByName(host), 443);
			s.setSoTimeout(10000);
			s.getOutputStream().write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
			InputStream in = s.getInputStream();
			byte[] buffer = new byte[8192];
			int done = 0;
			do {
				int nb;
				try { nb = in.read(buffer); }
				catch (SocketTimeoutException e) { break; }
				if (nb <= 0) break;
				StringBuilder str = new StringBuilder();
				DebugUtil.dumpHex(str, buffer, 0, nb);
				System.out.println(str.toString());
				done += nb;
			} while (done < 4096);
			s.close();
			
			System.out.println(" ---- HTTP/1.1 h2 -----");
			
			s = SSLSocketFactory.getDefault().createSocket(InetAddress.getByName(host), 443);
			s.setSoTimeout(10000);
			s.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: Upgrade, HTTP2-Settings\r\nUpgrade: h2\r\nHTTP2-Settings:\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			in = s.getInputStream();
			done = 0;
			do {
				int nb;
				try { nb = in.read(buffer); }
				catch (SocketTimeoutException e) { break; }
				if (nb <= 0) break;
				StringBuilder str = new StringBuilder();
				DebugUtil.dumpHex(str, buffer, 0, nb);
				System.out.println(str.toString());
				done += nb;
			} while (done < 4096);
			s.close();
			
			System.out.println(" ---- HTTP/1.1 h2c over TLS -----");
			
			s = SSLSocketFactory.getDefault().createSocket(InetAddress.getByName(host), 443);
			s.setSoTimeout(10000);
			s.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: Upgrade, HTTP2-Settings\r\nUpgrade: h2c\r\nHTTP2-Settings:\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			in = s.getInputStream();
			done = 0;
			do {
				int nb;
				try { nb = in.read(buffer); }
				catch (SocketTimeoutException e) { break; }
				if (nb <= 0) break;
				StringBuilder str = new StringBuilder();
				DebugUtil.dumpHex(str, buffer, 0, nb);
				System.out.println(str.toString());
				done += nb;
			} while (done < 4096);
			s.close();
			
			System.out.println(" ---- HTTP/1.1 h2c -----");
			
			s = SocketFactory.getDefault().createSocket(InetAddress.getByName(host), 80);
			s.setSoTimeout(10000);
			s.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: Upgrade, HTTP2-Settings\r\nUpgrade: h2c\r\nHTTP2-Settings:\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			in = s.getInputStream();
			done = 0;
			do {
				int nb;
				try { nb = in.read(buffer); }
				catch (SocketTimeoutException e) { break; }
				if (nb <= 0) break;
				StringBuilder str = new StringBuilder();
				DebugUtil.dumpHex(str, buffer, 0, nb);
				System.out.println(str.toString());
				done += nb;
			} while (done < 4096);
			s.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
}
