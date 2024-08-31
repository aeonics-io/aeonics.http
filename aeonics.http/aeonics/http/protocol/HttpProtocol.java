package aeonics.http.protocol;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedDeque;

import aeonics.entity.Message;
import aeonics.manager.Network;
import aeonics.util.Callback;

/**
 * All protocol handlers should call the {@link #watch(HttpProtocol)} method to maintain hard references to it until the connection is closed.
 */
public interface HttpProtocol
{
	static Collection<HttpProtocol> active = new ConcurrentLinkedDeque<>();
	static void watch(HttpProtocol p)
	{
		p.connection().onClose().then((x, c) -> active.remove(p));
		if( p.connection().active() ) active.add(p);
	}
	
	public Network.Connection connection();
	
	public Callback<Message, HttpProtocol> onRequest();
}
