package net.lecousin.framework.network.http.client;

import java.io.IOException;
import java.net.URI;

import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.provider.IOProvider;
import net.lecousin.framework.io.provider.IOProviderFrom;
import net.lecousin.framework.io.util.EmptyReadable;
import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.HTTPResponse;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.entity.MimeEntity;

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
			public IO.Readable provideIOReadable(Priority priority) throws IOException {
				try (HTTPClient client = HTTPClient.create(from)) {
					HTTPResponse response =
						client.sendAndReceive(new HTTPRequest().get().setURI(from), null, BinaryEntity::new, 10)
						.blockResult(0);
					response.checkSuccess();
					MimeEntity entity = response.getEntity();
					if (entity instanceof BinaryEntity)
						return ((BinaryEntity)entity).getContent();
					return new EmptyReadable(from.toString(), priority);
				} catch (Exception e) {
					throw IO.error(e);
				}
			}
			
		};
	}

}
