package net.lecousin.framework.network.http.client;

import java.io.IOException;
import java.net.URI;

import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.provider.IOProvider;
import net.lecousin.framework.io.provider.IOProviderFrom;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPRequest.Method;

// skip checkstyle: AbbreviationAsWordInName
/**
 * IOProvider from a HTTP(S) URL.
 */
public class HTTPIOProvider implements IOProviderFrom.Readable<URI> {

	@Override
	public IOProvider.Readable get(URI from) {
		return new IOProvider.Readable() {

			@Override
			public String getDescription() {
				return from.toString();
			}

			@Override
			public IO.Readable provideIOReadable(byte priority) throws IOException {
				try (HTTPClient client = HTTPClient.create(from)) {
					return client.sendAndReceive(new HTTPRequest(Method.GET).setURI(from), true, false, 10)
						.blockResult(0).getMIME().getBodyReceivedAsInput();
				} catch (Exception e) {
					throw IO.error(e);
				}
			}
			
		};
	}

}
