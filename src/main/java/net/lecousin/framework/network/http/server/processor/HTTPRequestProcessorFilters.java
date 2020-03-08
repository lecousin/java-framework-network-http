package net.lecousin.framework.network.http.server.processor;

import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.http.server.HTTPRequestFilter;
import net.lecousin.framework.network.http.server.HTTPRequestProcessor;

/** Wrap a processor with pre-filters and post-filters. */
public class HTTPRequestProcessorFilters implements HTTPRequestProcessor {

	protected List<HTTPRequestFilter> preFilters = new LinkedList<>();
	protected List<HTTPRequestFilter> postFilters = new LinkedList<>();
	protected HTTPRequestProcessor processor;
	
	/** Constructor. */
	public HTTPRequestProcessorFilters(HTTPRequestProcessor processor) {
		this.processor = processor;
	}
	
	
	@Override
	public void process(HTTPRequestContext ctx) {
		for (HTTPRequestFilter filter : preFilters) {
			filter.filter(ctx);
			if (ctx.getResponse().getReady().isDone())
				break;
		}
		
		if (!ctx.getResponse().getReady().isDone())
			processor.process(ctx);
		
		if (!postFilters.isEmpty())
			ctx.getResponse().getReady().thenStart("HTTP post filters", Task.getCurrentPriority(), () -> {
				for (HTTPRequestFilter filter : postFilters)
					filter.filter(ctx);
			}, false);
	}
	
}
