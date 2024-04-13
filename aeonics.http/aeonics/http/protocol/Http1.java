package aeonics.http.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import aeonics.data.Data;
import aeonics.entity.Message;
import aeonics.http.HttpServer;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.manager.Network;
import aeonics.util.Callback;
import aeonics.util.Json;
import aeonics.util.StringUtils;

public class Http1 implements HttpProtocol
{
	private Network.Connection connection = null;
	public Network.Connection connection() { return connection; }

	private Callback<Message> onRequest = new Callback<Message>();
	public Callback<Message> onRequest() { return onRequest; }
	
	private AtomicBoolean busy = new AtomicBoolean(false);
	private State parseState = new State();
	
	public Http1(Network.Connection connection)
	{
		this.connection = connection;
		HttpProtocol.watch(this);
		
		this.connection.onReady().then((c) ->
		{
			while( c.hasNext() )
			{
		         if (!busy.compareAndSet(false, true)) return;
		         
		         try
		         {
					for( byte[] data = c.next(); data != null; data = c.next() )
					{
						Message m = parse(data, parseState);
						if( m != null )
						{
							m.connection(connection());
							onRequest().trigger(m);
						}
					} 
		         } 
		         catch(HttpParseException e)
		         {
		        	 Manager.of(Logger.class).fine(HttpServer.class, "Http request parsing error: {}", e.getMessage());
		        	 try { c.close(); } catch(Exception x) { }
		         }
		         finally
		         { 
		             busy.set(false);
		         }
		     }
		});
	}
	
	public static class State
	{
		int mark, i, contentLength, length;
		Mode mode = Mode.MODE_1_START;
		Data request = null;
		String lastName = null;
		ByteArrayOutputStream partial = new ByteArrayOutputStream();
		
		int uriSize, headerSize, bodySize;
		boolean isCR = false; // used in case a \r\n is split in between
		boolean isEncoded = false; // used in case a %xx is split in between
		
		public void reset()
		{
			mark = i = length = 0;
			uriSize = headerSize = bodySize = 0;
			isCR = isEncoded = false;
			contentLength = -1;
			lastName = null;
			request = Data.map()
				.put("method", "")
				.put("path", "")
				.put("get", Data.map())
				.put("headers", Data.map())
				.put("post", Data.map())
				.put("files", Data.map())
				.put("body", "");
			mode = Mode.MODE_1_START;
			partial.reset();
		}
		public State() { reset(); }
	}
	
	static enum Mode
	{
		MODE_1_START,
		MODE_2_METHOD,
		MODE_3_URI,
		MODE_4_QUERYSTRING_NAME,
		MODE_4_QUERYSTRING_VALUE,
		MODE_5_VERSION,
		MODE_6_HEADER_NAME,
		MODE_6_HEADER_VALUE,
		MODE_7_BODY,
		MODE_8_COMPLETE,
	}
	
	public static class HttpParseException extends Exception
	{
		public HttpParseException(String s, int status)
		{
			super(s);
			this.status = status;
		}
		private int status = 500;
		public int status() { return status; }
	}
	
	static class Incomplete extends RuntimeException { }
	
	public static int maxURISize = 8000; // rfc7230#section-3.1.1
	public static int maxHeaderSize = 4096; // 4KB
	public static int maxBodySize = 5242880; // 5MB
	
	/**
	 * Attempts to parse the input HTTP request
	 * @param data some fragment of data, it may be a partial request fragment
	 * @param state the current parsing state
	 * @return a populated message if the request is complete, or null if more data is needed
	 * @throws HttpParseException if an invalid request is encountered
	 */
	public static Message parse(final byte[] data, final State state) throws HttpParseException
	{
		if( data.length == 0 ) return null;
		
		state.mark = state.i = 0;
		state.length = data.length;
		
		try
		{
			switch(state.mode)
			{
				case MODE_1_START: _1_eatWhitespace(data, state);
				case MODE_2_METHOD: _2_parseMethod(data, state);
				case MODE_3_URI: _3_parseURI(data, state);
				case MODE_4_QUERYSTRING_NAME:
				case MODE_4_QUERYSTRING_VALUE: _4_parseQueryString(data, state);
				case MODE_5_VERSION: _5_parseVersion(data, state);
				case MODE_6_HEADER_NAME: 
				case MODE_6_HEADER_VALUE: _6_parseHeaders(data, state);
				case MODE_7_BODY: _7_parseBody(data, state);
				default: break;
			}
		}
		catch(Incomplete i) { }
		
		if( state.mode == Mode.MODE_8_COMPLETE )
		{
			Message m = new Message(state.request.asString("method") + state.request.asString("path"));
			m.content(state.request);
			m.metadata().put("size", state.uriSize + state.headerSize + state.bodySize);
			state.reset();
			return m;
		}
		else
			return null;
	}
	
	private static void _1_eatWhitespace(byte[] data, State state) throws HttpParseException
	{
		byte b = 0;
		
		// eat leading \r\n
		for( b = data[state.i]; state.i < state.length; state.i++, state.uriSize++, b = data[state.i] )
		{
			if( b == '\r' || b == '\n' || b == ' ' )
			{
				if( state.uriSize > maxURISize )
					throw new HttpParseException("First line too long", 414);
				continue;
			}
			
			state.mark = state.i;
			state.mode = Mode.MODE_2_METHOD;
			return;
		}
		throw new Incomplete();
	}
	
	private static void _2_parseMethod(byte[] data, State state) throws HttpParseException
	{
		byte b = 0;
		
		for( ; state.i < state.length; state.i++, state.uriSize++ )
		{
			if( state.uriSize > maxURISize )
				throw new HttpParseException("First line too long", 414);
			
			b = data[state.i];
			if( b < 'A' || b > 'Z' )
			{
				if( b != ' ' ) throw new HttpParseException("Invalid method character " + (int)b, 400);
				
				if( state.partial.size() > 0 )
				{
					state.partial.write(data, state.mark, state.i - state.mark);
					state.request.put("method", new String(state.partial.toByteArray(), StandardCharsets.US_ASCII));
					state.partial.reset();
				}
				else
				{
					if( state.i == state.mark ) throw new HttpParseException("Missing method", 400);
					state.request.put("method", new String(data, state.mark, state.i - state.mark, StandardCharsets.US_ASCII));
				}
				
				state.mark = ++state.i;
				state.mode = Mode.MODE_3_URI;
				return;
			}
		}
		state.partial.write(data, state.mark, state.length - state.mark);
		throw new Incomplete();
	}
	
	private static void _3_parseURI(byte[] data, State state) throws HttpParseException
	{
		byte b = 0;

		for( ; state.i < state.length; state.i++, state.uriSize++ )
		{
			if( state.uriSize > maxURISize )
				throw new HttpParseException("First line too long", 414);
			
			b = data[state.i];
			if( b == '?' || b == ' ' )
			{
				String path;
				if( state.partial.size() > 0 )
				{
					state.partial.write(data, state.mark, state.i - state.mark);
					path = new String(state.partial.toByteArray(), StandardCharsets.UTF_8);
					state.partial.reset();
				}
				else
				{
					if( state.i == state.mark ) throw new HttpParseException("Missing path", 400);
					path = new String(data, state.mark, state.i - state.mark, StandardCharsets.UTF_8);
				}
				
				if( state.isEncoded )
				{
					path = java.net.URLDecoder.decode(path, StandardCharsets.UTF_8);
					state.isEncoded = false;
				}
				
				if( path.charAt(0) != '/' )
				{
					// rfc7230#section-5.3.2
					if( path.startsWith("http://") ) path = path.substring(Math.max(7, path.indexOf('/', 7)));
					else if( path.startsWith("https://") ) path = path.substring(Math.max(8, path.indexOf('/', 8)));
				}
				state.request.put("path", path);
				
				state.mark = ++state.i;
				state.mode = (b == '?' ? Mode.MODE_4_QUERYSTRING_NAME : Mode.MODE_5_VERSION);
				return;
			}
			else if( b == '\r' || b == '\n' )
				throw new HttpParseException("Unexpected EOL", 400);
			else if( b == '%' || b == '+')
				state.isEncoded = true;
		}
		state.partial.write(data, state.mark, state.length - state.mark);
		throw new Incomplete();
	}
	
	private static void _4_parseQueryString(byte[] data, State state) throws HttpParseException
	{
		// switch fall through
		if( state.mode != Mode.MODE_4_QUERYSTRING_NAME && state.mode != Mode.MODE_4_QUERYSTRING_VALUE ) return;
		
		byte b = 0;
		for( ; state.i < state.length; state.i++, state.uriSize++ )
		{
			if( state.uriSize > maxURISize )
				throw new HttpParseException("First line too long", 414);
			
			b = data[state.i];
			if( b == '=' ) // from name to value
			{
				if( state.mode != Mode.MODE_4_QUERYSTRING_NAME )
					throw new HttpParseException("Illegal character in query string value", 400);
				
				if( state.partial.size() > 0 )
				{
					state.partial.write(data, state.mark, state.i - state.mark);
					state.lastName = new String(state.partial.toByteArray(), StandardCharsets.US_ASCII);
					state.partial.reset();
				}
				else
				{
					if( state.i == state.mark ) throw new HttpParseException("Empty query string name", 400);
					state.lastName = new String(data, state.mark, state.i - state.mark, StandardCharsets.US_ASCII);
				}
				
				if( state.isEncoded )
				{
					state.lastName = java.net.URLDecoder.decode(state.lastName, StandardCharsets.UTF_8);
					state.isEncoded = false;
				}
				
				state.mark = state.i+1;
				state.mode = Mode.MODE_4_QUERYSTRING_VALUE;
			}
			else if( b == '&' || b == ' ' ) // end value start new name (or end)
			{
				if( state.mode == Mode.MODE_4_QUERYSTRING_NAME ) // name without value
				{
					if( state.partial.size() > 0 )
					{
						state.partial.write(data, state.mark, state.i - state.mark);
						state.lastName = new String(state.partial.toByteArray(), StandardCharsets.US_ASCII);
						state.partial.reset();
					}
					else
					{
						if( state.i == state.mark ) continue; // empty name and value in case of &&
						state.lastName = new String(data, state.mark, state.i - state.mark, StandardCharsets.US_ASCII);
					}
					
					if( state.isEncoded )
					{
						state.lastName = java.net.URLDecoder.decode(state.lastName, StandardCharsets.UTF_8);
						state.isEncoded = false;
					}
					
					state.request.get("get").put(state.lastName, Data.empty());
					
					state.lastName = null;
					state.mark = state.i+1;
				}
				else
				{
					String value;
					if( state.partial.size() > 0 )
					{
						state.partial.write(data, state.mark, state.i - state.mark);
						value = new String(state.partial.toByteArray(), StandardCharsets.US_ASCII);
						state.partial.reset();
					}
					else
					{
						if( state.i == state.mark ) value = null;
						else value = new String(data, state.mark, state.i - state.mark, StandardCharsets.US_ASCII);
					}
					
					if( state.isEncoded )
					{
						value = java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
						state.isEncoded = false;
					}
					
					state.request.get("get").put(state.lastName, value);
					
					state.lastName = null;
					state.mark = state.i+1;
					state.mode = Mode.MODE_4_QUERYSTRING_NAME;
				}
				
				if( b == ' ' )
				{
					state.mark = ++state.i;
					state.mode = Mode.MODE_5_VERSION;
					return;
				}
			}
			else if( b == '\r' || b == '\n' )
				throw new HttpParseException("Unexpected EOL", 400);
			else if( b == '%' || b == '+')
				state.isEncoded = true;
		}
		state.partial.write(data, state.mark, state.length - state.mark);
		throw new Incomplete();
	}
	
	private static void _5_parseVersion(byte[] data, State state) throws HttpParseException
	{
		if( state.length - state.mark + state.partial.size() < 10 ) // 10 chars = HTTP/1.1\r\n
		{
			state.partial.write(data, state.mark, state.length - state.mark);
			throw new Incomplete(); 
		}
		
		int offset = 10 - state.partial.size();
		state.partial.write(data, state.mark, offset);
		String version = new String(state.partial.toByteArray(), StandardCharsets.US_ASCII);
		state.partial.reset();
		
		if( !version.startsWith("HTTP/") )
			throw new HttpParseException("Invalid version", 400);
		
		int major = version.charAt(5) - '0';
		int minor = version.charAt(7) - '0';
		if( major < 0 || major > 9 || version.charAt(6) != '.' || minor < 0 || minor > 9 )
			throw new HttpParseException("Invalid version", 400);
		
		state.request.put("version", major + (minor / 10.0));
		
		if( version.charAt(8) != '\r' || version.charAt(9) != '\n' )
			throw new HttpParseException("Missing required EOL", 400);
		
		state.i = state.mark += offset;
		state.mode = Mode.MODE_6_HEADER_NAME;
		return;
	}
	
	private static void _6_parseHeaders(byte[] data, State state) throws HttpParseException
	{
		byte b = 0;
		for( ; state.i < state.length; state.i++, state.headerSize++ )
		{
			if( state.headerSize > maxHeaderSize )
				throw new HttpParseException("Headers too long", 431);
				
			b = data[state.i];
			if( b == '\r' || state.isCR )
			{
				// if it breaks between \r\n
				state.isCR = true;
				if( state.i == state.length-1 )
				{
					state.partial.write(data, state.mark, state.i - state.mark); // not until length to ignore \r
					throw new Incomplete();
				}
				if( b == '\r' )
					b = data[++state.i];
				if( b != '\n' )
					throw new HttpParseException("Malformed EOL", 400);
				
				if( state.mode == Mode.MODE_6_HEADER_NAME )
				{
					if( state.partial.size() > 0 || state.i - state.mark > 1 ) throw new HttpParseException("Invalid headers", 400);
					
					// end of headers
					state.isCR = false;
					state.lastName = null;
					state.mark = ++state.i;
					state.mode = Mode.MODE_7_BODY;
					return;
				}
				else
				{
					String value;
					if( state.partial.size() > 0 )
					{
						state.partial.write(data, state.mark, state.i - state.mark);
						value = new String(state.partial.toByteArray(), StandardCharsets.US_ASCII);
						state.partial.reset();
					}
					else
					{
						if( state.i == state.mark ) value = null;
						else value = new String(data, state.mark, state.i - state.mark, StandardCharsets.US_ASCII);
					}
					value = value.trim();
					
					String name = state.lastName.toLowerCase();
					if( state.request.get("headers").containsKey(name) )
						state.request.get("headers").put(name, state.request.get("headers").asString(name) + "," + value);
					else
						state.request.get("headers").put(name, value);
					
					state.isCR = false;
					state.lastName = null;
					state.mark = state.i+1;
					state.mode = Mode.MODE_6_HEADER_NAME;
				}
			}
			else if( b == ':' && state.mode == Mode.MODE_6_HEADER_NAME )
			{
				if( state.partial.size() > 0 )
				{
					state.partial.write(data, state.mark, state.i - state.mark);
					state.lastName = new String(state.partial.toByteArray(), StandardCharsets.US_ASCII);
					state.partial.reset();
				}
				else
				{
					if( state.i == state.mark ) throw new HttpParseException("Empty query string name", 400);
					state.lastName = new String(data, state.mark, state.i - state.mark, StandardCharsets.US_ASCII);
				}
				
				state.mark = state.i+1;
				state.mode = Mode.MODE_6_HEADER_VALUE;
			}
		}
		state.partial.write(data, state.mark, state.length - state.mark);
		throw new Incomplete();
	}
	
	private static void _7_parseBody(byte[] data, State state) throws HttpParseException
	{
		if( !state.request.get("headers").containsKey("host") )
			throw new HttpParseException("Missing Host header", 400);
		if( state.request.get("headers").containsKey("transfer-encoding") )
			throw new HttpParseException("Unsupported header: Transfer-Encoding", 415);
		
		if( state.contentLength < 0 )
			state.contentLength = state.request.get("headers").asInt("content-length");
		if( state.length - state.mark + state.partial.size() < state.contentLength )
		{
			state.partial.write(data, state.mark, state.length - state.mark);
			throw new Incomplete();
		}
		state.partial.write(data, state.mark, state.contentLength - state.partial.size());
		// from here on, the entire body is stored in state.partial
		
		// =====================
		// ATTEMPT TO PARSE BODY
		// =====================
		
		String contentType = state.request.get("headers").asString("content-type");
		data = state.partial.toByteArray();
		state.partial.reset();
		
		if( contentType.startsWith("application/x-www-form-urlencoded") )
		{
			decodeUrlencodedBody(data, state.request);
		}
		else if( contentType.startsWith("application/json") )
		{
			Data post = Json.decode(new String(data, StandardCharsets.UTF_8));
			if( post.isMap() )
				state.request.put("post", post);
		}
		else if( contentType.startsWith("multipart/form-data;") )
		{
			decodeMultipartBody(data, state.request, contentType);
		}
		else if( contentType.isEmpty() )
		{
			// check if this is not x-www-form-urlencoded anyway
			boolean amp = false, equal = false;
			for( byte b : data )
			{
				if( b == '&' ) { amp = true; if( equal ) break; }
				else if( b == '=' )  { equal = true; if( amp ) break; }
			}
			
			if( amp && equal )
				decodeUrlencodedBody(data, state.request);
			else
				state.request.put("body", new String(data, StandardCharsets.ISO_8859_1));
		}
		else
			state.request.put("body", new String(data, StandardCharsets.ISO_8859_1));
		
		state.mode = Mode.MODE_8_COMPLETE;
	}
	
	private static void decodeUrlencodedBody(byte[] data, Data request) throws HttpParseException
	{
		boolean isEncoded = false, isKey = true;
		byte b = 0;
		String key = null;
		int i = 0, mark = 0;
		for( ; i < data.length; i++)
		{
			b = data[i];
			if( b == '%' || b == '+' ) isEncoded = true;
			else if( b == '=' )
			{
				if( !isKey )
					throw new HttpParseException("Illegal character in urlencoded body value", 400);
				
				key = new String(data, mark, i - mark, StandardCharsets.US_ASCII);
				if( isEncoded )
				{
					key = java.net.URLDecoder.decode(key, StandardCharsets.UTF_8);
					isEncoded = false;
				}
				
				mark = i+1;
				isKey = false;
			}
			else if( b == '&' )
			{
				if( isKey ) // name without value
				{
					key = new String(data, mark, i - mark, StandardCharsets.US_ASCII);
					if( isEncoded )
					{
						key = java.net.URLDecoder.decode(key, StandardCharsets.UTF_8);
						isEncoded = false;
					}
					
					request.get("post").put(key, Data.empty());
					key = null;
					mark = i+1;
				}
				else
				{
					String value = new String(data, mark, i - mark, StandardCharsets.US_ASCII);
					
					if( isEncoded )
					{
						value = java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
						isEncoded = false;
					}
					
					request.get("post").put(key, value);
					
					key = null;
					mark = i+1;
					isKey = true;
				}
			}
		}
		
		if( isKey )
		{
			key = new String(data, mark, i - mark, StandardCharsets.US_ASCII);
			if( isEncoded )
			{
				key = java.net.URLDecoder.decode(key, StandardCharsets.UTF_8);
				isEncoded = false;
			}
			
			request.get("post").put(key, Data.empty());
		}
		else
		{
			String value = new String(data, mark, i - mark, StandardCharsets.US_ASCII);
			if( isEncoded )
			{
				value = java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
				isEncoded = false;
			}
			
			request.get("post").put(key, value);
		}
	}
	
	private static void decodeMultipartBody(byte[] data, Data request, String contentType) throws HttpParseException
	{
		String boundary = contentType.substring(contentType.indexOf('=')+1, contentType.length());
		boundary = StringUtils.trim(boundary, ' ', '"');
		
		if( boundary.length() < 1 || boundary.length() > 69 || data.length < boundary.length()+4 )
			throw new HttpParseException("Invalid multipart boundary", 400);
		
		byte[] bboundary = ("--"+boundary).getBytes(StandardCharsets.US_ASCII);
		Mode mode = Mode.MODE_6_HEADER_NAME;
		int i = 0, mark = 0;
		String name = null;
		String filename = null;
		String mime = null;
		String header = null;
		
		// skip preamble
		for( ; i < data.length-bboundary.length; i++ )
		{
			if( isAt(data, bboundary, i) )
				break;
		}
		mark = i;
		
		byte b = 0;
		for( ; i < data.length-2; i++ )
		{
			b = data[i];
			if( b == '-' )
			{
				if( isAt(data, bboundary, i) )
				{
					i += bboundary.length;
				
					// end of multipart, return
					boolean end = (data[i] == '-' && data[i+1] == '-' );
				
					if( !end && (data[i] != '\r' || data[++i] != '\n') )
						throw new HttpParseException("Malformed multipart line ending", 400);
					
					if( mode == Mode.MODE_7_BODY )
					{
						// end of previous part
						if( filename != null )
						{
							if( mime == null ) mime = "application/octet-stream";
							request.get("files").put(name, Data.map()
								.put("name", filename)
								.put("mime", mime)
								.put("content", new String(data, mark, i - mark - bboundary.length - (end?2:3), StandardCharsets.ISO_8859_1))
							);
						}
						else
							request.get("post").put(name, new String(data, mark, i - mark - bboundary.length - 3, StandardCharsets.ISO_8859_1));
						
						name = null;
						filename = null;
						mime = null;
						
						if( end ) return;
					}
					else if( end ) throw new HttpParseException("Premature end of multipart", 400);
						
					mode = Mode.MODE_6_HEADER_NAME;
					mark = i+1;
				}
			}
			else if( b == ':' && mode == Mode.MODE_6_HEADER_NAME )
			{
				header = new String(data, mark, i - mark, StandardCharsets.US_ASCII).toLowerCase();
				
				if( !header.equals("content-disposition") && !header.equals("content-type") )
					throw new HttpParseException("Illegal multipart header", 400);
					
				mark = i+1;
				mode = Mode.MODE_6_HEADER_VALUE;
			}
			else if( b == '\r' && mode == Mode.MODE_6_HEADER_VALUE )
			{
				if( data[++i] != '\n' ) throw new HttpParseException("Malformed multipart header line ending", 400);
				
				String value = new String(data, mark, i - mark - 1, StandardCharsets.ISO_8859_1);
				
				if( header.equals("content-type") ) mime = value.strip();
				else // content-disposition
				{
					String[] parts = StringUtils.split(value, ";");
					if( parts.length < 2 || !parts[0].strip().equalsIgnoreCase("form-data") )
						throw new HttpParseException("Invalid multipart content disposition", 400);
					
					// This is not totally correct parsing. It could lead to improper values if the header is malformed or contains weird values.
					// In such cases we dont really care, at worst it will not yield the proper intent which is as bad as the fact that the request is malformed.
					// There is no vulnerability possible here because any valid parsing could contain the same values as this laxist parsing anyway.
					
					if( parts[1].trim().toLowerCase().startsWith("name") ) name = StringUtils.trim(parts[1].trim().substring(4), ' ', '=', '"');
					else if( parts[1].trim().toLowerCase().startsWith("filename") ) filename = StringUtils.trim(parts[1].trim().substring(8), ' ', '=', '"', '*');
					if( parts.length > 2 && parts[2].trim().toLowerCase().startsWith("name") ) name = StringUtils.trim(parts[2].trim().substring(4), ' ', '=', '"');
					else if( parts.length > 2 && parts[2].trim().toLowerCase().startsWith("filename") ) filename = StringUtils.trim(parts[2].trim().substring(8), ' ', '=', '"', '*');
					
					if( name == null ) throw new HttpParseException("Invalid nameless multipart", 400);
				}
				
				mark = i+1;
				mode = Mode.MODE_6_HEADER_NAME;
			}
			else if( b == '\r' && mode == Mode.MODE_6_HEADER_NAME )
			{
				if( data[++i] != '\n' ) throw new HttpParseException("Malformed multipart header line ending", 400);
				if( data.length < (i+2+bboundary.length) ) 
					throw new HttpParseException("Malformed multipart body", 400);
				mark = i+1;
				mode = Mode.MODE_7_BODY;
			}
		}
	}
	
	private static boolean isAt(byte[] data, byte[] boundary, int i)
	{
		if( data.length-i < (boundary.length + 2)) return false;
		for( int j = 0; j < boundary.length; i++, j++)
			if( data[i] != boundary[j] ) 
				return false;
		return true;
	}
}
