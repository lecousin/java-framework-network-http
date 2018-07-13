package net.lecousin.framework.network.http;

import net.lecousin.framework.io.provider.IOProviderFromURL;
import net.lecousin.framework.network.http.client.HTTPIOProvider;
import net.lecousin.framework.plugins.CustomExtensionPoint;

/**
 * Initialization.
 */
public final class Init implements CustomExtensionPoint {

	/** Automatically called at init. */
	public Init() {
		HTTPIOProvider provider = new HTTPIOProvider();
		IOProviderFromURL.getInstance().registerProtocol("http", provider);
		IOProviderFromURL.getInstance().registerProtocol("https", provider);
	}

	@Override
	public boolean keepAfterInit() {
		return false;
	}
	
}
