package aeonics.http;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.function.Supplier;

import aeonics.data.Data;
import aeonics.entity.Message;
import aeonics.entity.Registry;
import aeonics.entity.Step.Origin;
import aeonics.entity.security.Token;
import aeonics.entity.security.User;
import aeonics.http.protocol.Http1;
import aeonics.http.protocol.HttpProtocol;
import aeonics.manager.Lifecycle;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.manager.Network;
import aeonics.manager.Scheduler;
import aeonics.manager.Network.SecurityOptions;
import aeonics.manager.Security;
import aeonics.template.Channel;
import aeonics.template.Parameter;
import aeonics.util.Callback;

public class HttpServer extends Origin
{
	private static class Type extends Origin.NetworkServer
	{
		private Scheduler.Cron.Type renewalCron = null;
		
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
					.withServerCertificate(crt.asString(), key.asString(), null));
				
				if( !crt.asString().startsWith("-----BEGIN ") || !key.asString().startsWith("-----BEGIN ") )
				{
					renewalCron = Manager.of(Scheduler.class).every(this::checkServerCertificate, 1, ChronoUnit.DAYS);
					server.onClose().then(Callback.once(() -> { Registry.of(Scheduler.Cron.class).remove(renewalCron); renewalCron = null; }));
				}
			}
			final boolean tls = server.isSecure();
			
			server.onAccept().then((connection, self) ->
			{
				HttpProtocol p = null;
				if( connection.alpn().isEmpty() || connection.alpn().equals("http/1.1") || connection.alpn().equals("http/1.0") )
					p = new Http1(connection);
				
				if( p == null )
				{
					try { connection.close(); } catch(Exception e) { /* ignore */ }
					Manager.of(Logger.class).warning(HttpServer.class, "Unsupported protocol version: {}", connection.alpn());
				}
				
				p.onRequest().then((m, protocol) -> 
				{
					try
					{
						authenticate(m);
						authorize(m);
						m.metadata().put("tls", tls);
						produce(m, "request");
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
		
		private void checkServerCertificate(ZonedDateTime now)
		{
			Network.Server server = server();
			if( server == null || server.security() == null || server.security().serverCertificate() == null ) return;
			
			Data crt = valueOf("certificate");
			Data key = valueOf("key");
			if( crt.isEmpty() || crt.asString().startsWith("-----BEGIN ") || key.asString().startsWith("-----BEGIN ") ) return;
			
			try
			{
				if( !SecurityOptions.certificate(crt.asString()).equals(server.security().serverCertificate().a) )
				{
					server.security().withServerCertificate(crt.asString(), key.asString(), null);
					Manager.of(Logger.class).info(HttpServer.class, "Certificate renewed");
				}
			}
			catch(Exception e)
			{
				Manager.of(Logger.class).warning(HttpServer.class, "Certificate renewal check failed", e);
			}
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
						if( current == null ) current = User.ANONYMOUS;
						else request.metadata().put("token", token);
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
				.rule(Parameter.Rule.DIGIT)
				.format(Parameter.Format.NUMBER)
				.defaultValue(80))
			.add(new Parameter("address")
				.summary("Address")
				.description("The ip address binding to listen on.")
				.format(Parameter.Format.TEXT)
				.defaultValue("0.0.0.0"))
			.add(new Parameter("certificate")
				.summary("Certificate")
				.description("The HTTPS full certificate chain to use. Leave this value empty if the connection should use plain HTTP instead of HTTPS.")
				.format(Parameter.Format.TEXT)
				.optional(true))
			.add(new Parameter("key")
				.summary("Private key")
				.description("The private key that matches the HTTPS certificate. Leave this value empty if the connection should use plain HTTP instead of HTTPS.")
				.format(Parameter.Format.TEXT)
				.optional(true))
			.onCreate((data, instance) -> 
			{
				((Origin.Type)instance).stop();
				if( Manager.of(Lifecycle.class).phase() == Lifecycle.Phase.RUN )
					((Origin.Type)instance).start(); 
			})
			.onUpdate((data, instance) -> 
			{ 
				((Origin.Type)instance).stop();
				if( Manager.of(Lifecycle.class).phase() == Lifecycle.Phase.RUN )
					((Origin.Type)instance).start(); 
			})
		;
	}	
}
