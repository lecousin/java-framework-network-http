package net.lecousin.framework.network.http.client;

import java.io.IOException;
import java.net.URI;

import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.provider.IOProvider;
import net.lecousin.framework.io.provider.IOProviderFrom;

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
				try {
					return HTTPClientUtil.download(from.toString(), 10, 1024 * 1024);
				} catch (Exception e) {
					throw IO.error(e);
				}
			}
			
		};
	}

}
