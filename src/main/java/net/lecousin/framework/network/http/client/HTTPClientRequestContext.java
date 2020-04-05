package net.lecousin.framework.network.http.client;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Function;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.out2in.OutputToInput;
import net.lecousin.framework.io.util.EmptyReadable;
import net.lecousin.framework.network.http1.HTTP1RequestCommandProducer;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.entity.BinaryFileEntity;
import net.lecousin.framework.network.mime.entity.EmptyEntity;
import net.lecousin.framework.network.mime.entity.MimeEntity;
import net.lecousin.framework.network.mime.entity.MimeEntityFactory;
import net.lecousin.framework.util.AbstractAttributesContainer;
import net.lecousin.framework.util.Pair;

/** Context while sending an HTTP request and receiving the response. */
public class HTTPClientRequestContext extends AbstractAttributesContainer {
	
	private HTTPClientRequestSender sender;
	private HTTPClientRequest request;
	private HTTPClientResponse response;
	private Pair<Long, AsyncProducer<ByteBuffer, IOException>> requestBody;
	private boolean throughProxy;
	private Async<IOException> requestSent = new Async<>();
	private MimeEntityFactory entityFactory;
	private Function<HTTPClientResponse, Boolean> onStatusReceived;
	private Function<HTTPClientResponse, Boolean> onHeadersReceived;
	private int maxRedirections = 0;
	
	/** Constructor. */
	public HTTPClientRequestContext(HTTPClientRequestSender sender, HTTPClientRequest request) {
		this.sender = sender;
		this.request = request;
		this.response = new HTTPClientResponse();
		requestSent.onError(response.getHeadersReceived()::error);
		requestSent.onCancel(response.getHeadersReceived()::cancel);
	}
	
	public HTTPClientRequestSender getSender() {
		return sender;
	}

	public void setSender(HTTPClientRequestSender sender) {
		this.sender = sender;
	}

	public HTTPClientRequest getRequest() {
		return request;
	}
	
	public HTTPClientResponse getResponse() {
		return response;
	}

	public int getMaxRedirections() {
		return maxRedirections;
	}

	public void setMaxRedirections(int maxRedirections) {
		this.maxRedirections = maxRedirections;
	}
	
	/** A redirection has been received, reset the context and send the request to the new location. */
	public void redirectTo(String location) throws URISyntaxException {
		URI u = new URI(location);
		if (u.getHost() == null) {
			// relative
			u = request.generateURI().resolve(u);
		}
		request.setURI(u);
		requestBody = null;
		throughProxy = false;
		requestSent = new Async<>();
		maxRedirections--;
		response.reset();
		sender.redirectTo(this, u);
	}

	public Pair<Long, AsyncProducer<ByteBuffer, IOException>> getRequestBody() {
		return requestBody;
	}

	public void setRequestBody(Pair<Long, AsyncProducer<ByteBuffer, IOException>> requestBody) {
		this.requestBody = requestBody;
	}

	public boolean isThroughProxy() {
		return throughProxy;
	}

	public void setThroughProxy(boolean throughProxy) {
		this.throughProxy = throughProxy;
	}

	public Async<IOException> getRequestSent() {
		return requestSent;
	}

	public MimeEntityFactory getEntityFactory() {
		return entityFactory;
	}

	public void setEntityFactory(MimeEntityFactory entityFactory) {
		this.entityFactory = entityFactory;
	}
	
	public void applyFilters(List<HTTPClientRequestFilter> filters) {
		for (HTTPClientRequestFilter filter : filters)
			filter.filter(request, response);
	}
	
	public AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> prepareRequestBody() {
		MimeEntity entity = request.getEntity();
		if (entity != null)
			return entity.createBodyProducer();
		return new AsyncSupplier<>(new Pair<>(Long.valueOf(0), new AsyncProducer.Empty<>()), null);
	}
	
	/** Receive the response from the server as a BinaryEntity with an OutputToInput so the body can be read while it is received. */
	public void receiveAsBinaryEntity(int maxBodyInMemory) {
		this.entityFactory = (parent, headers) -> {
			BinaryEntity entity = new BinaryEntity(parent, headers);
			IOInMemoryOrFile io = new IOInMemoryOrFile(maxBodyInMemory, Task.getCurrentPriority(), "BinaryEntity");
			entity.setContent(new OutputToInput(io, io.getSourceDescription()));
			return entity;
		};
		response.getBodyReceived().onError(error -> {
			if (response.getEntity() != null)
				((OutputToInput)((BinaryEntity)response.getEntity()).getContent()).signalErrorBeforeEndOfData(error);
		});
		response.getBodyReceived().onSuccess(() -> {
			if (response.getEntity() instanceof EmptyEntity) {
				// empty body, entityFactory was not called
				BinaryEntity entity = new BinaryEntity(null, response.getHeaders());
				entity.setContent(new EmptyReadable("empty body", Task.Priority.NORMAL));
				response.setEntity(entity);
			}
		});
	}
	
	/** Received the response as a BinaryEntity or EmptyEntity. */
	public void receiveAsBinaryEntity() {
		this.entityFactory = BinaryEntity::new;
	}
	
	/** Receive the response as a BinaryFileEntity to the given file. */
	public void downloadTo(File file) {
		this.entityFactory = (parent, headers) -> {
			BinaryFileEntity entity = new BinaryFileEntity(parent, headers);
			entity.setFile(file);
			return entity;
		};
	}

	public Function<HTTPClientResponse, Boolean> getOnStatusReceived() {
		return onStatusReceived;
	}

	public void setOnStatusReceived(Function<HTTPClientResponse, Boolean> onStatusReceived) {
		this.onStatusReceived = onStatusReceived;
	}

	public Function<HTTPClientResponse, Boolean> getOnHeadersReceived() {
		return onHeadersReceived;
	}

	public void setOnHeadersReceived(Function<HTTPClientResponse, Boolean> onHeadersReceived) {
		this.onHeadersReceived = onHeadersReceived;
	}
	
	@Override
	public String toString() {
		return "HTTPClientRequestContext: " + HTTP1RequestCommandProducer.generateString(request);
	}

}
