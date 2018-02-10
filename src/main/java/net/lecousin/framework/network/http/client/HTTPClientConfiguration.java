package net.lecousin.framework.network.http.client;

import java.net.ProxySelector;
import java.net.SocketOption;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javax.net.ssl.SSLContext;

import net.lecousin.framework.network.SocketOptionValue;
import net.lecousin.framework.network.http.client.interceptors.ConnectionInterceptor;
import net.lecousin.framework.network.http.client.interceptors.EnsureHostInterceptor;
import net.lecousin.framework.network.http.client.interceptors.UserAgentInterceptor;

/** Configuration for an HTTP client. */
public class HTTPClientConfiguration {

	public static final HTTPClientConfiguration basicConfiguration = new HTTPClientConfiguration();
	public static final HTTPClientConfiguration defaultConfiguration = new HTTPClientConfiguration();
	
	static {
		basicConfiguration.appendInterceptor(new UserAgentInterceptor(HTTPClient.USER_AGENT, false));
		basicConfiguration.appendInterceptor(new EnsureHostInterceptor());
		
		defaultConfiguration.setConnectionTimeout(30000);
		defaultConfiguration.setReceiveTimeout(60000);
		defaultConfiguration.appendInterceptor(new UserAgentInterceptor(HTTPClient.USER_AGENT, false));
		defaultConfiguration.appendInterceptor(new ConnectionInterceptor(true));
		defaultConfiguration.appendInterceptor(new EnsureHostInterceptor());
		defaultConfiguration.setProxySelector(ProxySelector.getDefault());
	}
	
	public HTTPClientConfiguration() {
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public HTTPClientConfiguration(HTTPClientConfiguration copy) {
		this.connectionTimeout = copy.connectionTimeout;
		this.receiveTimeout = copy.receiveTimeout;
		for (SocketOptionValue<?> so : copy.socketOptions)
			socketOptions.add(new SocketOptionValue(so.getOption(), so.getValue()));
		this.interceptors.addAll(copy.getInterceptors());
		this.proxySelector = copy.proxySelector;
		this.sslContext = copy.sslContext;
	}
	
	private int connectionTimeout = 0;
	private int receiveTimeout = 0;
	private LinkedList<SocketOptionValue<?>> socketOptions = new LinkedList<>();
	private LinkedList<HTTPRequestInterceptor> interceptors = new LinkedList<>();
	private ProxySelector proxySelector;
	private SSLContext sslContext = null;
	
	public int getConnectionTimeout() {
		return connectionTimeout;
	}
	
	public void setConnectionTimeout(int timeout) {
		connectionTimeout = timeout;
	}
	
	public int getReceiveTimeout() {
		return receiveTimeout;
	}
	
	public void setReceiveTimeout(int timeout) {
		receiveTimeout = timeout;
	}

	public List<SocketOptionValue<?>> getSocketOptions() {
		return socketOptions;
	}
	
	public SocketOptionValue<?>[] getSocketOptionsArray() {
		return socketOptions.toArray(new SocketOptionValue<?>[socketOptions.size()]);
	}
	
	/** Set an option. */
	@SuppressWarnings("unchecked")
	public <T> void setSocketOption(SocketOptionValue<T> option) {
		for (@SuppressWarnings("rawtypes") SocketOptionValue o : socketOptions)
			if (o.getOption().equals(option.getOption())) {
				o.setValue(option.getValue());
				return;
			}
		socketOptions.add(option);
	}
	
	/** Set an option. */
	@SuppressWarnings("unchecked")
	public <T> void setSocketOption(SocketOption<T> option, T value) {
		for (@SuppressWarnings("rawtypes") SocketOptionValue o : socketOptions)
			if (o.getOption().equals(option)) {
				o.setValue(value);
				return;
			}
		socketOptions.add(new SocketOptionValue<T>(option, value));
	}
	
	/** Get the value for an option or null if not set. */
	@SuppressWarnings("unchecked")
	public <T> T getSocketOption(SocketOption<T> option) {
		for (@SuppressWarnings("rawtypes") SocketOptionValue o : socketOptions)
			if (o.getOption().equals(option))
				return (T)o.getValue();
		return null;
	}
	
	public SSLContext getSSLContext() {
		return sslContext;
	}
	
	public void setSSLContext(SSLContext context) {
		sslContext = context;
	}
	
	/** Return the list of interceptors. */
	public List<HTTPRequestInterceptor> getInterceptors() {
		synchronized (interceptors) {
			return new ArrayList<>(interceptors);
		}
	}
	
	/** Add an interceptor. */
	public void appendInterceptor(HTTPRequestInterceptor interceptor) {
		synchronized (interceptors) {
			interceptors.addLast(interceptor);
		}
	}

	/** Add an interceptor. */
	public void insertInterceptorFirst(HTTPRequestInterceptor interceptor) {
		synchronized (interceptors) {
			interceptors.addFirst(interceptor);
		}
	}
	
	public ProxySelector getProxySelector() {
		return proxySelector;
	}
	
	public void setProxySelector(ProxySelector selector) {
		proxySelector = selector;
	}
	
}
