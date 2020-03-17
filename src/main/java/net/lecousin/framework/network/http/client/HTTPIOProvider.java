package net.lecousin.framework.network.http.client;

import java.io.IOException;
import java.net.URI;

import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.provider.IOProvider;
import net.lecousin.framework.io.provider.IOProviderFrom;
import net.lecousin.framework.io.util.EmptyReadable;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.entity.MimeEntity;

//skip checkstyle: AbbreviationAsWordInName
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
				try {
					HTTPClient client = HTTPClient.getDefault();
					HTTPClientRequestContext ctx = new HTTPClientRequestContext(client, new HTTPClientRequest(from).get());
					ctx.receiveAsBinaryEntity();
					ctx.setMaxRedirections(10);
					client.send(ctx);
					ctx.getResponse().getBodyReceived().blockThrow(0);
					ctx.getResponse().checkSuccess();
					MimeEntity entity = ctx.getResponse().getEntity();
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
