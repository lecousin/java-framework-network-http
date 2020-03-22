package net.lecousin.framework.network.http.header;

import net.lecousin.framework.network.mime.header.HeaderValues;

public class AlternativeServices extends HeaderValues<AlternativeService> {

	@Override
	protected AlternativeService newValue() {
		return new AlternativeService();
	}
	
}
