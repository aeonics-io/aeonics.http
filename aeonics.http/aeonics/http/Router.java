package aeonics.http;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.function.Supplier;

import aeonics.data.Data;
import aeonics.entity.Entity;
import aeonics.entity.Message;
import aeonics.entity.Registry;
import aeonics.entity.Step.Action;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.manager.Monitor;
import aeonics.template.Channel;
import aeonics.template.Parameter;
import aeonics.template.Relationship;
import aeonics.util.StringUtils;
import aeonics.util.Tuples.Tuple;

public class Router extends Action
{
	private static class Type extends Action.Type
	{
		@Override
		public Message process(Message message, String input, String output)
		{
			if( !output.equals("response") )
				return null;
			if( !StringUtils.simplePathMatches(valueOf("host").asString(), message.content().get("headers").asString("host")) )
				return null;
			if( !StringUtils.simplePathMatches(valueOf("path").asString(), message.content().asString("path")) )
				return null;
			
			Data response = null;
			long start = System.nanoTime();
			
			try
			{
				response = requestFilters(message);
				if( response == null ) response = processRequest(message);
				if( response == null ) throw new HttpException(404);
			}
			catch(Exception t)
			{
				if( !(t instanceof HttpException) )
					Manager.of(Logger.class).info(Endpoint.class, t);
				response = handleError(t, message);
			}

			try
			{
				response = dataResponse(response);
				Data overwrite = responseFilters(message, response);
				if( overwrite != null ) return finalizeResponse(message, overwrite, start);
				else return finalizeResponse(message, response, start);
			}
			catch(Exception t)
			{
				if( !(t instanceof HttpException) )
					Manager.of(Logger.class).info(Endpoint.class, t);
				return finalizeResponse(message, handleError(t, message), start);
			}
		}
		
		private Message finalizeResponse(Message request, Data response, long start)
		{
			Message r = new Message(request.key());
			r.connection(request.connection());
			r.user(request.user());
			r.content(dataResponse(response));
			
			Data h = r.content().get("headers");
			if( !h.containsKey("Date") ) h.put("Date", getDate());
			if( !h.containsKey("Connection") )
			{
				if( !request.content().get("headers").isEmpty("connection") )
					h.put("Connection", request.content().get("headers").get("connection"));
				else
					h.put("Connection", "keep-alive");
			}
			r.content().put("version", request.content().get("version"));
			h.put("Server-Timing", "app;dur=" + ((System.nanoTime() - start)/1000000L));
			
			return r;
		}
		
		private Data dataResponse(Data data)
		{
			if( data == null ) data = Data.map();
			if( !data.isMap() || !data.asBool("isHttpResponse") ) data = Data.map().put("body", data).put("mime", "application/json");
			
			if( !data.isNumber("code") ) data.put("code", !data.isMap("body") && !data.isList("body") && data.isEmpty("body") ? 204 : 200);
			if( !data.isMap("headers") ) data.put("headers", Data.map());
			if( !data.get("headers").isEmpty("Content-Type") && data.isEmpty("mime") ) data.put("mime", data.get("headers").get("Content-Type"));
			if( data.isEmpty("mime") ) data.put("mime", data.isMap("body") || data.isList("body") ? "application/json" : "application/octet-stream");
			if( data.isEmpty("status") ) data.put("status", getStatus(data.asInt("code")));
			
			data.put("isHttpResponse", true);
			return data;
		}
		
		private Data requestFilters(Message request) throws Exception
		{
			if( valueOf("allfilters").asBool() )
			{
				for( Filter.Type f : Registry.of(Filter.class) )
				{
					try( AutoCloseable a = Manager.of(Monitor.class).ns(f) )
					{
						Data response = f.filter(request);
						if( response != null ) return response;
					}
				}
			}
			else
			{
				for( Tuple<Entity, Data> r : relations("filters") )
				{
					try( AutoCloseable a = Manager.of(Monitor.class).ns(r.a) )
					{
						Data response = ((Filter.Type)r.a).filter(request);
						if( response != null ) return response;
					}
				}
			}
			return null;
		}
		
		private Data processRequest(Message request) throws Exception
		{
			String method = request.content().asString("method");
			String path = request.content().asString("path");
			
			if( valueOf("allendpoints").asBool() )
			{
				for( Endpoint.Type e : Registry.of(Endpoint.class) )
				{
					if( !e.matchesMethod(method) ) continue;
					if( !e.matchesPath(path) ) continue;
	
					try( AutoCloseable ns = Manager.of(Monitor.class).ns(e) )
					{
						return Objects.requireNonNullElse(e.process(request), Data.empty());
					}
				}
			}
			else
			{
				for( Tuple<Entity, Data> x : relations("endpoints") )
				{
					Endpoint.Type e = (Endpoint.Type) x.a;
					if( e == null ) continue;
					
					if( !e.matchesMethod(method) ) continue;
					if( !e.matchesPath(path) ) continue;
	
					try( AutoCloseable ns = Manager.of(Monitor.class).ns(e) )
					{
						return Objects.requireNonNullElse(e.process(request), Data.empty());
					}
				}
			}
			
			return null;
		}
		
		private Data responseFilters(Message request, Data response) throws Exception
		{
			if( valueOf("allfilters").asBool() )
			{
				for( Filter.Type f : Registry.of(Filter.class) )
				{
					try( AutoCloseable a = Manager.of(Monitor.class).ns(f) )
					{
						Data override = f.filter(request, response);
						if( override != null ) return override;
					}
				}
			}
			else
			{
				for( Tuple<Entity, Data> r : relations("filters") )
				{
					try( AutoCloseable a = Manager.of(Monitor.class).ns(r.a) )
					{
						Data override = ((Filter.Type)r.a).filter(request, response);
						if( override != null ) return override;
					}
				}
			}
			
			return null;
		}
		
		private Data handleError(Exception error, Message request)
		{
			Data data = Data.map().put("isHttpResponse", true);
			if( error instanceof HttpException )
			{
				data.put("code", ((HttpException) error).code)
					.put("body", ((HttpException) error).data)
					.put("mime", "application/json");
			}
			else
			{
				data.put("code", 500)
					.put("body", error.getMessage())
					.put("mime", "text/plain");
			}
			
			Manager.of(Logger.class).fine(Endpoint.class, "HTTP CODE {} : {} {}", 
					data.get("code"),
					request.content().asString("method"),
					request.content().asString("path"));

			return data;
		}
		
		private String getStatus(int code)
		{
			switch(code)
			{
				case 100: return "Continue";
				case 101: return "Switching Protocols";
				case 102: return "Processing";
				case 103: return "Early Hints";
				
				case 200: return "OK";
				case 201: return "Created";
				case 202: return "Accepted";
				case 203: return "Non-Authoritative Information";
				case 204: return "No Content";
				case 205: return "Reset Content";
				case 206: return "Partial Content";
				case 207: return "Multi-Status";
				case 208: return "Already Reported";
				case 226: return "IM Used";
				
				case 300: return "Multiple Choices";
				case 301: return "Moved Permanently";
				case 302: return "Found";
				case 303: return "See Other";
				case 304: return "Not Modified";
				case 305: return "Use Proxy";
				case 306: return "Switch Proxy";
				case 307: return "Temporary Redirect";
				case 308: return "Permanent Redirect";
				
				case 400: return "Bad Request";
				case 401: return "Unauthorized";
				case 402: return "Payment Required";
				case 403: return "Forbidden";
				case 404: return "Not Found";
				case 405: return "Method Not Allowed";
				case 406: return "Not Acceptable";
				case 407: return "Proxy Authentication Required";
				case 408: return "Request Timeout";
				case 409: return "Conflict";
				case 410: return "Gone";
				case 411: return "Length Required";
				case 412: return "Precondition Failed";
				case 413: return "Payload Too Large";
				case 414: return "URI Too Long";
				case 415: return "Unsupported Media Type";
				case 416: return "Range Not Satisfiable";
				case 417: return "Expectation Failed";
				case 418: return "I'm a teapot";
				case 421: return "Misdirected Request";
				case 422: return "Unprocessable Entity";
				case 423: return "Locked";
				case 424: return "Failed Dependency";
				case 425: return "Too Early";
				case 426: return "Upgrade Required";
				case 428: return "Precondition Required";
				case 429: return "Too Many Requests";
				case 431: return "Request Header Fields Too Large";
				case 451: return "Unavailable For Legal Reasons";
				
				case 500: return "Internal Server Error";
				case 501: return "Not Implemented";
				case 502: return "Bad Gateway";
				case 503: return "Service Unavailable";
				case 504: return "Gateway Timeout";
				case 505: return "HTTP Version Not Supported";
				case 506: return "Variant Also Negotiates";
				case 507: return "Insufficient Storage";
				case 508: return "Loop Detected";
				case 510: return "Not Extended";
				case 511: return "Network Authentication Required";
				
				default: return "Status Undefined";
			}
		}
		
		private static final String[] DAYS = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
	    private static final String[] MONTHS = {"Dec", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

	    private volatile String dateCache = null;
	    private volatile long dateAt = 0;
		private String getDate()
		{
			long currentTimeSeconds = System.currentTimeMillis() / 1000;
			if( currentTimeSeconds != dateAt )
			{
				// Example: Wed, 21 Oct 2015 07:28:00 GMT
	
				ZonedDateTime now = ZonedDateTime.now(ZoneId.of("GMT"));
				
				StringBuilder sb = new StringBuilder();
				sb.append(DAYS[now.getDayOfWeek().getValue()]);
				sb.append(", ");
				if( now.getDayOfMonth() < 10 ) sb.append('0');
				sb.append(now.getDayOfMonth());
				sb.append(' ');
				sb.append(MONTHS[now.getMonthValue()]);
				sb.append(' ');
				sb.append(now.getYear());
				sb.append(' ');
				if( now.getHour() < 10 ) sb.append('0');
				sb.append(now.getHour());
				sb.append(':');
				if( now.getMinute() < 10 ) sb.append('0');
				sb.append(now.getMinute());
				sb.append(':');
				if( now.getSecond() < 10 ) sb.append('0');
				sb.append(now.getSecond());
				sb.append(" GMT");
				
				dateCache = sb.toString();
				dateAt = currentTimeSeconds;
			}
			return dateCache;
		}
	}

	@Override
	protected Class<? extends Router.Type> defaultTarget() { return Router.Type.class; }
	@Override
	protected Supplier<? extends Router.Type> defaultCreator() { return Router.Type::new; }

	@Override
	public Action.Template template()
	{
		return super.template()
			.input(new Channel("request")
				.summary("Request")
				.description("This channel expects a valid http request in input"))
			.output(new Channel("response")
				.summary("Response")
				.description("This channel will output the endpoint response to be sent to the client"))
			.creator(Type::new)
			.summary("Http Router")
			.description("Routes the HTTP request to the proper related endpoint.")
			.add(new Parameter("host")
				.summary("Virtual Host")
				.description("A wildcard filter to match the requested host. Use this parameter to apply virtual host routing. Default: '#'.")
				.format(Parameter.Format.TEXT)
				.rule(Parameter.Rule.WILDCARD_PATH)
				.optional(true)
				.defaultValue("#")
				)
			.add(new Parameter("path")
				.summary("Virtual Path")
				.description("A wildcard filter to restrict the delivered URLs. Default: '#'.")
				.format(Parameter.Format.TEXT)
				.rule(Parameter.Rule.WILDCARD_PATH)
				.optional(true)
				.defaultValue("#")
				)
			.add(new Relationship("filters")
				.category(Filter.class)
				.summary("Filters")
				.description("List of request and response filters to apply to this router and all related endpoints.")
				)
			.add(new Relationship("endpoints")
				.category(Endpoint.class)
				.summary("Endpoints")
				.description("List of endpoints accessible via this router.")
				)
			.add(new Parameter("allfilters")
				.summary("Use all filters")
				.description("If true, this router will use all filters in the registry regardless if they are listed as relationship.")
				.rule(Parameter.Rule.BOOLEAN)
				.format(Parameter.Format.BOOLEAN)
				.optional(true)
				.defaultValue(true)
				)
			.add(new Parameter("allendpoints")
					.summary("Use all endpoints")
					.description("If true, this router will use all endpoints in the registry regardless if they are listed as relationship.")
					.rule(Parameter.Rule.BOOLEAN)
					.format(Parameter.Format.BOOLEAN)
					.optional(true)
					.defaultValue(true)
				)
			;
	}
}
