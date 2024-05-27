package aeonics.http;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Supplier;

import aeonics.data.Data;
import aeonics.entity.Message;
import aeonics.entity.Origin;
import aeonics.entity.Registry;
import aeonics.entity.security.Token;
import aeonics.entity.security.User;
import aeonics.http.protocol.Http1;
import aeonics.http.protocol.HttpProtocol;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.manager.Network;
import aeonics.manager.Network.SecurityOptions;
import aeonics.manager.Security;
import aeonics.template.Channel;
import aeonics.template.Parameter;

public class HttpServer extends Origin
{
	private static class Type extends Origin.NetworkServer
	{
		@Override
		public Network.Server connect() throws Exception
		{
			Data crt = valueOf("certificate");
			Data key = valueOf("key");
			
			Network.Server server = null;
			if( crt.isEmpty() )
				server = Manager.of(Network.class).server(valueOf("address").asString(), valueOf("port").asInt());
			else
			{
				server = Manager.of(Network.class).server(valueOf("address").asString(), valueOf("port").asInt(), new SecurityOptions()
					.withAlpn(Arrays.asList("http/1.1","http/1.0"))
					.withServerCertificate(crt.asString(), key.asString()));
			}
			final boolean tls = server.isSecure();
			
			server.onAccept().then((connection) ->
			{
				HttpProtocol p = null;
				if( connection.alpn().isEmpty() || connection.alpn().equals("http/1.1") || connection.alpn().equals("http/1.0") )
					p = new Http1(connection);
				
				if( p == null )
				{
					try { connection.close(); } catch(Exception e) { /* ignore */ }
					Manager.of(Logger.class).warning(HttpServer.class, "Unsupported protocol version: {}", connection.alpn());
				}
				
				p.onRequest().then((m) -> 
				{
					try
					{
						authenticate(m);
						authorize(m);
						m.metadata().put("tls", tls);
						emit(m, "request");
					}
					catch(SecurityException e)
					{
						boolean isAuthenticated = m.content().get("headers").containsKey("authorization");
						StringBuilder sb = new StringBuilder();
						
						if( !isAuthenticated )
						{
							sb.append("HTTP/");
							sb.append(m.content().asString("version"));
							sb.append(" 401 Unauthorized\r\n");
							sb.append("WWW-Authenticate: Bearer scope=\"topic http\"\r\nContent-Length: 0\r\n\r\n");
						}
						else
						{
							sb.append("HTTP/");
							sb.append(m.content().asString("version"));
							sb.append(" 403 Forbidden\r\n");
							sb.append("Content-Length: 0\r\n\r\n");
						}
						
						try { m.connection().write(sb.toString()); }
						catch(IOException ioe)
						{
							try { m.connection().close(); } catch(Exception x) { /* ignore */ }
						}
					}
				});
			});
			
			return server;
		}
		
		/**
		 * Lazy token authentication.
		 * If a valid token with scope "http" is provided, the user is bound to the request.
		 * Otherwise, no error is thrown an the anonymous user is used.
		 * 
		 * The message metadata "token" property will contain the token (if any).
		 * 
		 * @param request the input request
		 */
		private void authenticate(Message request)
		{
			User.Type current = User.ANONYMOUS;
			if( request.content().get("headers").containsKey("authorization") )
			{
				String auth = request.content().get("headers").asString("authorization");
				if( auth.startsWith("Bearer ") )
				{
					Token token = Manager.of(Security.class).authenticate(auth.substring(7), true);
					if( token != null && token.isValid() && token.inScope("http") )
					{
						current = token.user();
						request.metadata().put("token", token);
					}
				}
			}
			request.user(current.id());
		}
		
		/**
		 * Delegate the security check to pre-authorize the request.
		 * 
		 * @param request the input request
		 */
		private void authorize(Message request)
		{
			if( !Manager.of(Security.class).granted(
				Registry.of(User.class).get(request.user()), 
				"http", 
				Data.map().put("method", request.content().asString("method"))
						  .put("path", request.content().asString("path"))) )
				throw new SecurityException("Access denied");
		}
	}

	@Override
	protected Class<? extends HttpServer.Type> defaultTarget() { return HttpServer.Type.class; }
	@Override
	protected Supplier<? extends HttpServer.Type> defaultCreator() { return HttpServer.Type::new; }

	@Override
	public Origin.Template template()
	{
		return super.template()
			.output(new Channel("request")
				.summary("Http requests")
				.description("This channel emits http requests that have been received from the network."))
			.summary("HTTP Server")
			.description("This data origin element acts as an HTTP server. It needs to be connected to a Router to process the requests and then send the reply to the message linked connection.")
			.add(new Parameter("port")
				.summary("Port")
				.description("The port number to listen on.")
				.defaultValue(Data.of(80)))
			.add(new Parameter("address")
				.summary("Address")
				.description("The ip address binding to listen on.")
				.defaultValue(Data.of("0.0.0.0")))
			.add(new Parameter("certificate")
				.summary("Certificate")
				.description("The HTTPS certificate to use. Leave this value empty if the connection should use plain HTTP instead of HTTPS.")
				.optional(true))
			.add(new Parameter("key")
				.summary("Private key")
				.description("The private key that matches the HTTPS certificate. Leave this value empty if the connection should use plain HTTP instead of HTTPS.")
				.optional(true))
			.builder((data, instance) -> { instance.stop(); instance.start(); Registry.add(instance); })
			.modifier((data, instance) -> { instance.stop(); instance.start(); })
		;
	}	
}
