package aeonics.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

import aeonics.data.Data;
import aeonics.entity.Message;
import aeonics.entity.Step.Destination;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.template.Channel;

public class HttpResponse extends Destination
{
	private static class Type extends Destination.Type
	{
		@Override
		public void process(Message message, String input)
		{
			if( !input.equals("response") ) return;
			if( message.connection() == null ) throw new IllegalStateException("The message does not have an active connection bound");
			
			int code = 0;
			try
			{
				Data c = message.content();
				Data h = c.get("headers");
				
				// FIX Content-Length AND Content-Type
				Charset charset = StandardCharsets.ISO_8859_1;
				String mime = c.asString("mime");
				if( !h.containsKey("Content-Encoding") && (mime.startsWith("text/") || mime.startsWith("application/json")) )
				{
					int i = mime.indexOf("charset=");
					if( i < 0 ) { mime += "; charset=UTF-8"; charset = StandardCharsets.UTF_8; }
					else
					{
						try { charset = Charset.forName(mime.substring(i)); }
						catch(Exception e) { Manager.of(Logger.class).fine(getClass(), e); }
					}
				}
				byte[] body = c.asString("body").getBytes(charset);
				h.put("Content-Length", body.length);
				if( body.length > 0 )
					h.put("Content-Type", mime);
				else
					h.remove("Content-Type");
				
				code = c.asInt("code");
				
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				StringBuilder sb = new StringBuilder();
				
				// STATUS LINE
				sb.append("HTTP/");
				sb.append(c.asString("version"));
				sb.append(' ');
				sb.append(code);
				sb.append(' ');
				sb.append(c.asString("status"));
				sb.append("\r\n");
				
				// HEADERS
				for( Map.Entry<String, Data> i : h.entrySet() )
				{
					if( i.getValue().isEmpty() ) continue;
					
					sb.append(i.getKey());
					sb.append(": ");
					
					if( i.getValue().isList() )
					{
						boolean first = true;
						for( Data v : i.getValue() )
						{
							if( first ) first = false;
							else sb.append(", ");
							sb.append(v.asString());
						}
					}
					else
						sb.append(i.getValue().asString());
					
					sb.append("\r\n");
				}
				sb.append("\r\n");
				
				// BODY
				out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
				out.write(body);
				
				try { message.connection().write(out.toByteArray()); }
				catch(Exception e)
				{
					// this can happen because the client closes the connection before the end of the response
					if( code >= 400 ) return;
					else throw e;
				}
				
				if( h.asString("Connection").equalsIgnoreCase("close") )
					message.connection().close();
			}
			catch(Exception e)
			{
				Manager.of(Logger.class).fine(getClass(), e);
				try { message.connection().close(); } catch(Exception x) { /* ignore */ }
			}
		}
	}

	@Override
	protected Class<? extends HttpResponse.Type> defaultTarget() { return HttpResponse.Type.class; }
	@Override
	protected Supplier<? extends HttpResponse.Type> defaultCreator() { return HttpResponse.Type::new; }

	@Override
	public Destination.Template template()
	{
		return super.template()
			.input(new Channel("response")
				.summary("Http response")
				.description("This channel expects a valid http response in input"))
			.creator(Type::new)
			.summary("Http Response")
			.description("Formats and sends an HTTP response to the origin network connection.")
			;
	}
}
