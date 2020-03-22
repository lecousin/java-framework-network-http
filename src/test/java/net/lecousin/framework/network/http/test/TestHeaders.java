package net.lecousin.framework.network.http.test;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.network.http.header.AlternativeService;
import net.lecousin.framework.network.mime.MimeException;
import net.lecousin.framework.network.mime.header.parser.Token;

import org.junit.Assert;
import org.junit.Test;

public class TestHeaders extends LCCoreAbstractTest {

	@Test
	public void testAlternativeService() throws MimeException {
		AlternativeService alt = new AlternativeService();
		alt.setProtocolId("h2");
		alt.setHostname("www.example.org");
		alt.setPort(80);
		alt.setMaxAge(10);
		Assert.assertEquals("h2=\"www.example.org:80\";ma=10", Token.toString(alt.generateTokens()));
		alt = new AlternativeService();
		alt.parseRawValue("h2=\"www.example.org:80\";ma=10");
		Assert.assertEquals("h2", alt.getProtocolId());
		Assert.assertEquals("www.example.org", alt.getHostname());
		Assert.assertEquals(80, alt.getPort());
		Assert.assertEquals(10, alt.getMaxAge());
		
		alt = new AlternativeService();
		alt.setProtocolId("h2");
		alt.setPort(80);
		alt.setMaxAge(10);
		Assert.assertEquals("h2=\":80\";ma=10", Token.toString(alt.generateTokens()));
		alt = new AlternativeService();
		alt.parseRawValue("h2=\":80\";ma=10");
		Assert.assertEquals("h2", alt.getProtocolId());
		Assert.assertNull(alt.getHostname());
		Assert.assertEquals(80, alt.getPort());
		Assert.assertEquals(10, alt.getMaxAge());
		
		alt = new AlternativeService();
		alt.setProtocolId("h2");
		alt.setHostname("www.example.org");
		alt.setMaxAge(10);
		Assert.assertEquals("h2=\"www.example.org\";ma=10", Token.toString(alt.generateTokens()));
		alt = new AlternativeService();
		alt.parseRawValue("h2=\"www.example.org\";ma=10");
		Assert.assertEquals("h2", alt.getProtocolId());
		Assert.assertEquals("www.example.org", alt.getHostname());
		Assert.assertEquals(0, alt.getPort());
		Assert.assertEquals(10, alt.getMaxAge());
		
		alt = new AlternativeService();
		alt.setProtocolId("h2");
		alt.setHostname("www.example.org");
		alt.setPort(80);
		Assert.assertEquals("h2=\"www.example.org:80\"", Token.toString(alt.generateTokens()));
		alt = new AlternativeService();
		alt.parseRawValue("h2=\"www.example.org:80\"");
		Assert.assertEquals("h2", alt.getProtocolId());
		Assert.assertEquals("www.example.org", alt.getHostname());
		Assert.assertEquals(80, alt.getPort());
		Assert.assertEquals(-1, alt.getMaxAge());
		
		alt = new AlternativeService();
		alt.setProtocolId("h:");
		alt.setHostname("www.example.org");
		alt.setPort(80);
		alt.setMaxAge(10);
		Assert.assertEquals("h%3A=\"www.example.org:80\";ma=10", Token.toString(alt.generateTokens()));
		alt = new AlternativeService();
		alt.parseRawValue("h%3A=\"www.example.org:80\";ma=10");
		Assert.assertEquals("h:", alt.getProtocolId());
		Assert.assertEquals("www.example.org", alt.getHostname());
		Assert.assertEquals(80, alt.getPort());
		Assert.assertEquals(10, alt.getMaxAge());
	}
	
}
