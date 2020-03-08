package net.lecousin.framework.network.http1.server;

import java.nio.ByteBuffer;

import net.lecousin.framework.network.http.HTTPRequest;
import net.lecousin.framework.network.http.exception.HTTPException;
import net.lecousin.framework.network.http.server.HTTPRequestContext;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.ServerProtocol;

/** A server protocol that can be used with the Upgrade mechanism (Section 6.7 of RFC7230) or a specific first request. */
public interface HTTP1ServerUpgradeProtocol extends ServerProtocol {

	/** Return the name of the protocol expected in the Upgrade header. */
	String getUpgradeProtocolToken();
	
	/** Check the request and return true if the upgrade can be done through the Upgrade mechanism.
	 * <p>
	 * If true is returned, the protocol will be upgraded.<br/>
	 * The client's attribute {@link HTTP1ServerProtocol#UPGRADED_PROTOCOL_ATTRIBUTE} will be set to this protocol.<br/>
	 * The client's attribute {@link HTTP1ServerProtocol#UPGRADED_PROTOCOL_REQUEST_CONTEXT_ATTRIBUTE} will be
	 * set with the {@link HTTPRequestContext} and a response is expected.<br/>
	 * The method {@link #startProtocol(TCPServerClient)} is called, and this method should make the response
	 * ready. After the method call, the client's attributes {@link HTTP1ServerProtocol#REQUEST_ATTRIBUTE} and
	 * {@link HTTP1ServerProtocol#UPGRADED_PROTOCOL_REQUEST_CONTEXT_ATTRIBUTE} will be removed and the new protocol
	 * has to manage the client's state with its own attributes.<br/>
	 * Any further data received from the client will be transfered to this protocol.
	 * </p> 
	 */
	boolean acceptUpgrade(TCPServerClient client, HTTPRequest request);
	
	/** Return true if the request is an upgrade request but without using the upgrade mechanism.
	 * <p>
	 * If true is returned, there is no HTTPRequestContext created and a HTTPResponse is not expected
	 * as the protocol may completely change.<br/>
	 * The method {@link #startProtocol(TCPServerClient)} is called, and at this time the client's attribute
	 * {@link HTTP1ServerProtocol#REQUEST_ATTRIBUTE} still contains the original HTTPRequest. After this method
	 * the client's attribute will be removed and it is up to the protocol to manage the client's state with
	 * its own attributes.<br/>
	 * Any further data received from the client will be transfered to this protocol.
	 * </p>
	 */
	boolean isUpgradeRequest(TCPServerClient client, HTTPRequest request, ByteBuffer data) throws HTTPException;
	
}
