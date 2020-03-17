package net.lecousin.framework.network.http.test;

import java.net.StandardSocketOptions;

import net.lecousin.framework.network.SocketOptionValue;
import net.lecousin.framework.network.http.client.HTTPClientConfiguration;
import net.lecousin.framework.network.http.client.filters.UserAgentFilter;
import net.lecousin.framework.network.test.AbstractNetworkTest;

import org.junit.Assert;
import org.junit.Test;

public class TestHTTPClientConfiguration extends AbstractNetworkTest {

	@Test
	public void testBasics() throws Exception {
		HTTPClientConfiguration config = new HTTPClientConfiguration();
		config.setSocketOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);
		config.setSocketOption(StandardSocketOptions.TCP_NODELAY, Boolean.FALSE);
		config.setSocketOption(StandardSocketOptions.SO_KEEPALIVE, Boolean.TRUE);
		config.setSocketOption(new SocketOptionValue<>(StandardSocketOptions.SO_BROADCAST, Boolean.TRUE));
		config.setSocketOption(new SocketOptionValue<>(StandardSocketOptions.SO_BROADCAST, Boolean.FALSE));
		config.setSocketOption(new SocketOptionValue<>(StandardSocketOptions.SO_RCVBUF, Integer.valueOf(1234)));
		config = new HTTPClientConfiguration(config);
		Assert.assertEquals(Boolean.FALSE, config.getSocketOption(StandardSocketOptions.TCP_NODELAY));
		Assert.assertEquals(Boolean.FALSE, config.getSocketOption(StandardSocketOptions.SO_BROADCAST));
		Assert.assertEquals(Boolean.TRUE, config.getSocketOption(StandardSocketOptions.SO_KEEPALIVE));
		Assert.assertEquals(Integer.valueOf(1234), config.getSocketOption(StandardSocketOptions.SO_RCVBUF));
		Assert.assertNull(config.getSocketOption(StandardSocketOptions.IP_MULTICAST_LOOP));
		config.getSocketOptions();
		config.setSSLContext(sslTest);
		config.insertFilterFirst(new UserAgentFilter("test", true));
	}
	
}
