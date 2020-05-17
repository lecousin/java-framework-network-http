package net.lecousin.framework.network.http2.test;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.IntFunction;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration.Protocol;
import net.lecousin.framework.network.http.client.HTTPClientConnection;
import net.lecousin.framework.network.http.client.HTTPClientConnection.OpenConnection;
import net.lecousin.framework.network.http.client.HTTPClientRequest;
import net.lecousin.framework.network.http.client.HTTPClientRequestContext;
import net.lecousin.framework.network.http.client.HTTPClientRequestSender;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;
import net.lecousin.framework.network.http.test.AbstractTestHttpServer;
import net.lecousin.framework.network.http.test.ProcessorForTests;
import net.lecousin.framework.network.http1.client.HTTP1ClientConnection;
import net.lecousin.framework.network.http1.server.HTTP1ServerProtocol;
import net.lecousin.framework.network.http2.client.ClientRequestStream;
import net.lecousin.framework.network.http2.client.HTTP2Client;
import net.lecousin.framework.network.http2.frame.HTTP2Frame;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.server.HTTP2ServerProtocol;
import net.lecousin.framework.network.server.protocol.ALPNServerProtocol;
import net.lecousin.framework.network.server.protocol.ServerProtocol;
import net.lecousin.framework.network.ssl.SSLConnectionConfig;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runners.Parameterized.Parameters;

public class TestHttp2Server extends AbstractTestHttpServer {

	public enum TestCase {
		PRIOR_KNOWLEDGE,
		UPGRADE,
		ALPN;
	}
	
	@Parameters(name = "ssl = {0}, case = {1}")
	public static Collection<Object[]> parameters() {
		return addTestParameter(AbstractTestHttpServer.parameters(), TestCase.PRIOR_KNOWLEDGE, TestCase.UPGRADE, TestCase.ALPN);
	}
	
	public TestHttp2Server(boolean useSSL, TestCase testCase) {
		super(useSSL);
		this.testCase = testCase;
	}

	private TestCase testCase;
	private HTTP1ServerProtocol protocol1;
	private HTTP2ServerProtocol protocol2;
	
	@Before
	public void configureLogs() {
		resumeLogging();
	}
	
	@Override
	protected void stopLogging() {
		LCCore.getApplication().getLoggerFactory().getLogger(HTTP1ServerProtocol.class).setLevel(Level.ERROR);
		LCCore.getApplication().getLoggerFactory().getLogger(HTTP2ServerProtocol.class).setLevel(Level.ERROR);
		LCCore.getApplication().getLoggerFactory().getLogger(HTTP2Client.class).setLevel(Level.ERROR);
	}
	
	@Override
	protected void resumeLogging() {
		LCCore.getApplication().getLoggerFactory().getLogger(HTTP1ServerProtocol.class).setLevel(Level.DEBUG);
		LCCore.getApplication().getLoggerFactory().getLogger(HTTP2ServerProtocol.class).setLevel(useSSL ? Level.DEBUG : Level.TRACE);
		LCCore.getApplication().getLoggerFactory().getLogger(HTTP2Client.class).setLevel(useSSL ? Level.DEBUG : Level.TRACE);
	}

	private boolean makeClientAggressive = false;
	private boolean makeServerRestrictive = false;
	private boolean makeServerOpenToConcurrency = false;
	private boolean useHttp1Client = false;

	@Override
	protected ServerProtocol createProtocol(HTTPRequestProcessor processor) {
		protocol1 = new HTTP1ServerProtocol(processor);
		protocol2 = new HTTP2ServerProtocol(protocol1);
		if (makeClientAggressive || makeServerRestrictive)
			protocol2.getSettings().setMaxConcurrentStreams(2);
		else if (makeServerOpenToConcurrency)
			protocol2.getSettings().setMaxConcurrentStreams(-1);
		return protocol1;
	}
	
	@Override
	protected ALPNServerProtocol[] getALPNProtocols() {
		return new ALPNServerProtocol[] {
			protocol2,
			protocol1
		};
	}

	@Override
	protected void enableRangeRequests() {
		protocol2.enableRangeRequests(true);
	}
	
	@SuppressWarnings("deprecation")
	@Override
	protected HTTPClientRequestSender createClient() throws Exception {
		if (useHttp1Client) {
			HTTP1ClientConnection client = new HTTP1ClientConnection(2, clientConfig);
			return client;
		}
		HTTP2Client client = new HTTP2Client(clientConfig);
		switch (testCase) {
		case PRIOR_KNOWLEDGE:
			client.connectWithPriorKnowledge(serverAddress, "localhost", useSSL).blockThrow(0);
			break;
		case UPGRADE:
			client.connectWithUpgrade(serverAddress, "localhost", useSSL).blockThrow(0);
			break;
		case ALPN:
			Assume.assumeTrue(SSLConnectionConfig.ALPN_SUPPORTED);
			Assume.assumeTrue(useSSL);
			client.connectWithALPN(serverAddress, "localhost").blockThrow(0);
			break;
		}
		if (makeClientAggressive)
			client.getHTTP2Connection().getRemoteSettings().setMaxConcurrentStreams(-1);
		return client;
	}
	
	@Test
	public void testHttp1Request() throws Exception {
		Assume.assumeTrue(TestCase.PRIOR_KNOWLEDGE.equals(testCase));
		useHttp1Client = true;
		testGetStatus();
	}
	
	@Test
	public void testAggressiveClient() throws Exception {
		Assume.assumeTrue(TestCase.PRIOR_KNOWLEDGE.equals(testCase));
		makeClientAggressive = true;
		try {
			testSeveralGetRequests();
			throw new AssertionError();
		} catch (Throwable e) {
			// this is expected as the server should close this aggressive client
		}
	}
	
	@Test
	public void testOnlyTwoConcurrentStreams() throws Exception {
		Assume.assumeTrue(TestCase.PRIOR_KNOWLEDGE.equals(testCase));
		makeServerRestrictive = true;
		testSeveralGetRequests();
	}
	
	@Test
	public void testNoConcurrentStreamsLimit() throws Exception {
		Assume.assumeTrue(TestCase.PRIOR_KNOWLEDGE.equals(testCase));
		makeServerOpenToConcurrency = true;
		testSeveralGetRequests();
	}
	
	@Test
	public void testSendInvalidFrames() throws Exception {
		Assume.assumeTrue(TestCase.PRIOR_KNOWLEDGE.equals(testCase));
		startServer(new ProcessorForTests());
		
		// test send SETTINGS on data stream
		sendInvalidFrame(true, id -> new HTTP2Frame.Writer() {
			private boolean sent = false;
			@Override
			public byte getType() { return HTTP2FrameHeader.TYPE_SETTINGS; }
			@Override
			public int getStreamId() { return id; }
			@Override
			public boolean canProduceSeveralFrames() { return false; }
			@Override
			public boolean canProduceMore() { return !sent; }
			@Override
			public ByteArray produce(int maxFrameSize, ByteArrayCache cache) {
				byte[] b = new byte[HTTP2FrameHeader.LENGTH];
				HTTP2FrameHeader.write(b, 0, 0, HTTP2FrameHeader.TYPE_SETTINGS, (byte)0, id);
				sent = true;
				return new ByteArray.Writable(b, true);
			}
		});
		
		// test send HEADERS on connection stream
		sendInvalidFrame(false, id -> new HTTP2Frame.Writer() {
			private boolean sent = false;
			@Override
			public byte getType() { return HTTP2FrameHeader.TYPE_SETTINGS; }
			@Override
			public int getStreamId() { return id; }
			@Override
			public boolean canProduceSeveralFrames() { return false; }
			@Override
			public boolean canProduceMore() { return !sent; }
			@Override
			public ByteArray produce(int maxFrameSize, ByteArrayCache cache) {
				byte[] b = new byte[HTTP2FrameHeader.LENGTH];
				HTTP2FrameHeader.write(b, 0, 0, HTTP2FrameHeader.TYPE_HEADERS, (byte)0, id);
				sent = true;
				return new ByteArray.Writable(b, true);
			}
		});
		
		// test send giant payload
		sendInvalidFrame(true, id -> new HTTP2Frame.Writer() {
			private boolean sent = false;
			@Override
			public byte getType() { return HTTP2FrameHeader.TYPE_HEADERS; }
			@Override
			public int getStreamId() { return id; }
			@Override
			public boolean canProduceSeveralFrames() { return false; }
			@Override
			public boolean canProduceMore() { return !sent; }
			@Override
			public ByteArray produce(int maxFrameSize, ByteArrayCache cache) {
				byte[] b = new byte[HTTP2FrameHeader.LENGTH];
				HTTP2FrameHeader.write(b, 0, Integer.MAX_VALUE, HTTP2FrameHeader.TYPE_HEADERS, (byte)0, id);
				sent = true;
				return new ByteArray.Writable(b, true);
			}
		});
	}
	
	private void sendInvalidFrame(boolean needDataStream, IntFunction<HTTP2Frame.Writer> frameProvider) throws Exception {
		try (HTTP2Client client = (HTTP2Client)createClient()) {
			IAsync<IOException> send;
			if (needDataStream) {
				send = new Async<>();
				ClientRequestStream stream = new ClientRequestStream(client.getHTTP2Connection(), new HTTPClientRequestContext(client, new HTTPClientRequest("localhost", serverAddress.getPort(), useSSL)));
				client.getHTTP2Connection().reserveCompressionContextAndOpenStream(stream).thenStart("Send HTTP/2 headers", Priority.NORMAL, reservation -> {
					int streamId = reservation.getValue2().intValue();
					client.getHTTP2Connection().sendFrame(frameProvider.apply(streamId), false, false).onDone((Async<IOException>)send);
				}, send);
			} else {
				send = client.getHTTP2Connection().sendFrame(frameProvider.apply(0), false, false);
			}
			send.blockThrow(0);
			Async<Exception> sp = new Async<>();
			client.getTCPConnection().onclosed(sp::unblock);
			sp.blockThrow(5000);
			Assert.assertTrue(sp.isDone() || client.getTCPConnection().isClosed());
		}
	}
	
	@Test
	public void testWrongHttp2Connections() throws Exception {
		Assume.assumeTrue(TestCase.PRIOR_KNOWLEDGE.equals(testCase) || TestCase.ALPN.equals(testCase));
		startServer(new ProcessorForTests());
		HTTPClientConfiguration config = clientConfig;
		if (TestCase.PRIOR_KNOWLEDGE.equals(testCase)) {
			config = new HTTPClientConfiguration(clientConfig);
			config.setAllowedProtocols(Arrays.asList(Protocol.HTTP1S, Protocol.HTTP1));
		}
		testWrongHttp2Connection("PRI * HTTP/2.1\r\n\r\nSM\r\n\r\n", config);
		testWrongHttp2Connection("PRO * HTTP/2.0\r\n\r\nSM\r\n\r\n", config);
		testWrongHttp2Connection("PRI x HTTP/2.0\r\n\r\nSM\r\n\r\n", config);
		testWrongHttp2Connection("PRI * HTTP/2.0\r\nX: x\r\n\r\nSM\r\n\r\n", config);
		testWrongHttp2Connection("PRI * HTTP/2.0\r\n\r\nSX\r\n\r\n", config);
		testWrongHttp2Connection("PRI * HTTP/2.0\r\n\r\nXM\r\n\r\n", config);
		testWrongHttp2Connection("PRI * HTTP/2.0\r\n\rX\nSM\r\n\r\n", config);
		testWrongHttp2Connection("PRI * HTTP/2.0\r\n\r\nSMX\r\n\r\n", config);
		testWrongHttp2Connection("PRI * HTTP/2.0\r\n\r\nSM\rX\n\r\n", config);
		testWrongHttp2Connection("PRI * HTTP/2.0\r\n\r\nSM\r\nX\r\n", config);
		testWrongHttp2Connection("PRI * HTTP/2.0\r\n\r\nSM\r\n\rX\n", config);
		if (TestCase.PRIOR_KNOWLEDGE.equals(testCase))
			testWrongHttp2Connection("PRI * HTTP/2.0\r\n\r\nS", config);
	}
	
	private void testWrongHttp2Connection(String upgradeString, HTTPClientConfiguration clientConfig) throws Exception {
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(TestHttp2Server.class);
		OpenConnection conn = HTTPClientConnection.openDirectConnection(serverAddress, "localhost", useSSL, clientConfig, logger);
		TCPClient tcp = conn.getClient();
		IAsync<IOException> connect = conn.getConnect();
		connect.blockThrow(0);
		tcp.send(ByteBuffer.wrap(upgradeString.getBytes(StandardCharsets.US_ASCII)), 5000).blockThrow(0);
		try {
			ByteArrayIO io = tcp.getReceiver().readUntil((byte)'\n', 1024, 5000).blockResult(0);
			String line = IOUtil.readFullyAsStringSync(io, StandardCharsets.US_ASCII);
			Assert.assertTrue(line.contains(" 400 "));
		} catch (Exception e) {
			if (!testCase.equals(TestCase.ALPN) || (!(e instanceof ClosedChannelException) && !(e instanceof EOFException)))
				throw new Exception("Error reading bad request response", e);
		}
		tcp.close();
	}
	
	@Test
	public void testPing() throws Exception {
		startServer(new ProcessorForTests());
		try (HTTP2Client client = (HTTP2Client)createClient()) {
			Async<IOException> done = new Async<>();
			client.ping(10000, ok -> { if (ok.booleanValue()) done.unblock(); else done.error(new IOException("ping timeout")); });
			done.blockThrow(15000);
			Assert.assertTrue(done.isDone());
		}
	}
	
	@Test
	public void testUnknownFrame() throws Exception {
		startServer(new ProcessorForTests());
		try (HTTP2Client client = (HTTP2Client)createClient()) {
			IAsync<IOException> send = client.getHTTP2Connection().sendFrame(new HTTP2Frame.Writer() {
				private boolean sent = false;
				@Override
				public byte getType() { return (byte)99; }
				@Override
				public int getStreamId() { return 0; }
				@Override
				public boolean canProduceSeveralFrames() { return false; }
				@Override
				public boolean canProduceMore() { return !sent; }
				@Override
				public ByteArray produce(int maxFrameSize, ByteArrayCache cache) {
					byte[] b = new byte[HTTP2FrameHeader.LENGTH];
					HTTP2FrameHeader.write(b, 0, 0, (byte)99, (byte)0, 0);
					sent = true;
					return new ByteArray.Writable(b, true);
				}
			}, false, false);
			send.blockThrow(0);
			// send a ping, connection should still be operational
			Async<IOException> done = new Async<>();
			client.ping(10000, ok -> { if (ok.booleanValue()) done.unblock(); else done.error(new IOException("ping timeout")); });
			done.blockThrow(15000);
			Assert.assertTrue(done.isDone());
		}
	}
	
}
