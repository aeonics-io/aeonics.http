package aeonics.http.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import aeonics.data.Data;
import aeonics.entity.Message;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.manager.Network;
import aeonics.manager.Timeout;
import aeonics.manager.Timeout.Tracker;
import aeonics.util.Callback;
import aeonics.util.Json;

public class Websocket implements HttpProtocol
{
	private static final int MAX_BODY_SIZE = 5242880; // 5MB
	public static final int OP_CONTINUE = 0;
	public static final int OP_TEXT = 1;
	public static final int OP_BINARY = 2;
	public static final int OP_CLOSE = 8;
	public static final int OP_PING = 9;
	public static final int OP_PONG = 10;
	
	private Network.Connection connection = null;
	public Network.Connection connection() { return connection; }

	private Callback<Message, HttpProtocol> onRequest = new Callback<>(this);
	public Callback<Message, HttpProtocol> onRequest() { return onRequest; }
	
	private AtomicBoolean busy = new AtomicBoolean(false);
	private State parseState = new State();
	
	public Websocket(Network.Connection connection)
	{
		this.connection = connection;
		HttpProtocol.watch(this);
		
		this.connection.onReady().clear();
		this.connection.onReady().then((_null, c) ->
		{
			while( c.hasNext() )
			{
		         if (!busy.compareAndSet(false, true)) return;
		         
		         try
		         {
					for( byte[] data = c.next(); data != null; data = c.next() )
					{
						Message m;
						do
						{
							m = parse(data, parseState);
							if( m != null )
							{
								m.connection(connection());
								onRequest().trigger(m);
							}
							data = null;
						} while( m != null );
					} 
		         } 
		         catch(WebsocketParseException e)
		         {
		        	 Manager.of(Logger.class).fine(Websocket.class, "Websocket request parsing error: {}", e.getMessage());
		        	 try { c.close(); } catch(Exception x) { /* ignore */ }
		         }
		         finally
		         { 
		             busy.set(false);
		         }
		     }
		});
		
		// send PING request every 10s
		Manager.of(Timeout.class).watch(new Tracker<Websocket>("Websocket Pinger", this)
		{
			int count = 0;
			public long delay()
			{
				if( connection == null || !connection.active() ) return -1;
				
				if( count > 0 )
					send(null, OP_PING);
				count++;
				return Math.max(0, 10_000);
			}
		});
	}
	
	public static class State
	{
		ByteArrayOutputStream input = new ByteArrayOutputStream();
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		
		int OPCODE = 0;
		
		public void reset()
		{
			OPCODE = 0;
			payload.reset();
		}
		public State() { reset(); }
	}
	
	public static class WebsocketParseException extends Exception
	{
		public WebsocketParseException(String s, int status)
		{
			super(s);
			this.status = status;
		}
		private int status = 1002;
		public int status() { return status; }
	}
	
	/**
	 * Attempts to parse the input request
	 * @param data some fragment of data, it may be a partial request fragment
	 * @param state the current parsing state
	 * @return a populated message if the request is complete, or null if more data is needed
	 * @throws WebsocketParseException if an invalid request is encountered
	 */
	public Message parse(final byte[] data, final State state) throws WebsocketParseException
	{
		if( data != null && data.length > 0 )
			state.input.writeBytes(data);
		
		try
		{
			do
			{
				if( _1_parseFrame(state) )
				{
					byte[] payload = state.payload.toByteArray();
					state.reset();
					
					if( payload.length > 0 )
					{
						Message m = new Message("websocket");
						
						if( (payload[0] == '[' && payload[payload.length-1] == ']') || (payload[0] == '{' && payload[payload.length-1] == '}') )
							m.content(Json.decode(new String(payload, StandardCharsets.ISO_8859_1)));
						else
							m.content(Data.of(new String(payload, StandardCharsets.ISO_8859_1)));
						
						return m;
					}
				}
			} while( true );
		}
		catch(Incomplete i) { /* normal */ }
		catch(WebsocketParseException w)
		{
			Manager.of(Logger.class).finest(Websocket.class, w.getMessage());
			close(w.status());
		}
		return null;
	}
	
	/**
	 * Attempts to parse a frame.
	 * If the frame is a control frame, it is handled immediately, but parsing continues.
	 * @param state the internal state
	 * @return true if a successful DATA frame was parsed
	 */
	private boolean _1_parseFrame(final State state) throws WebsocketParseException
	{
		int minFrameSize = 6;
		if( state.input.size() < minFrameSize ) throw new Incomplete();
		
		byte[] data = state.input.toByteArray();
		int index = 0;
		int b = data[index++] & 0xFF;
		int FIN = (b >> 7);
		int OPCODE = (b & 0xF);
		
		if( FIN == 0 && OPCODE > 2 )
			throw new WebsocketParseException("Invalid OPCODE with FIN", 1002);
		
		b = data[index++] & 0xFF;
		int MASK = (b >> 7);
		if( MASK == 0 )
			throw new WebsocketParseException("Client masking is mandatory", 1002);
		
		long PAYLOAD_LEN = (b & 0x7F);
		if( PAYLOAD_LEN == 126 )
		{
			minFrameSize = 8;
			if( data.length < minFrameSize ) throw new Incomplete();
			PAYLOAD_LEN = (((data[index++] & 0xFF) << 8) | (data[index++] & 0xFF)); 
		}
		else if( PAYLOAD_LEN == 127 )
		{
			minFrameSize = 14;
			if( data.length < minFrameSize ) throw new Incomplete();
			PAYLOAD_LEN = (((data[index++] & 0xFF) << 56) | ((data[index++] & 0xFF) << 48) | ((data[index++] & 0xFF) << 40) | ((data[index++] & 0xFF) << 32) | ((data[index++] & 0xFF) << 24) | ((data[index++] & 0xFF) << 16)| ((data[index++] & 0xFF) << 8) | (data[index++] & 0xFF));
		}
		
		if( PAYLOAD_LEN + state.payload.size() > MAX_BODY_SIZE )
			throw new WebsocketParseException("Request too large", 1009);
		
		minFrameSize = (int) (PAYLOAD_LEN + 6);
		if( data.length < minFrameSize ) throw new Incomplete();
		
		byte[] MASKING_KEY = new byte[] { data[index++], data[index++], data[index++], data[index++] };
		byte[] frame = Arrays.copyOfRange(data, index, (int) (index + PAYLOAD_LEN));
		index = (int) (index + PAYLOAD_LEN);
		
		for( int i = 0; i < frame.length; i++ )
			frame[i] = (byte) ((frame[i] ^ MASKING_KEY[i % 4]) & 0xFF);
		
		// frame complete : put remaining bytes in the input
		state.input.reset();
		if( data.length > index ) state.input.writeBytes(Arrays.copyOfRange(data, index, data.length));
		
		_2_processFrame(FIN, OPCODE, frame, state);
		
		if( FIN == 1 && OPCODE <= OP_BINARY )
			return true;
		else
			return false;
	}
	
	private void _2_processFrame(final int FIN, final int OPCODE, final byte[] frame, final State state) throws WebsocketParseException
	{
		switch(OPCODE)
		{
			case 8: // CLOSE
				send(frame, OP_CLOSE);
				try { this.connection.close(); } catch(Exception e) { /* ignore */ }
				break;
			case 9: // PING
				send(frame, OP_PONG);
				break;
			case 10: // PONG
				// nothing to do
				break;
			case 0:
			case 1:
			case 2:
				state.payload.writeBytes(frame);
				break;
			default:
				throw new WebsocketParseException("Invalid OPCODE", 1002);
		}
	}
	
	public void close(int error)
	{
		send(new byte[] { (byte) (error >> 8), (byte) (error & 0xF) }, OP_CLOSE);
		try { this.connection.close(); } catch(Exception e) { /* ignore */ }
	}
	
	public synchronized void send(byte[] payload, int OPCODE)
	{
		try( ByteArrayOutputStream o = new ByteArrayOutputStream() )
		{
			if( payload == null || payload.length == 0 )
			{
				int b = 0x80 | (OPCODE & 0xF);
				o.write(b);
				o.write(0);
				this.connection.write(o.toByteArray());
				return;
			}
				
			int maxSendSize =  524288; // 512KB
			
			for( int start = 0; start < payload.length; start += maxSendSize )
			{
				// FIN
				int b = 0;
				if( start + maxSendSize >= payload.length )
					b = 0x80;
				
				// OPCODE
				if( start == 0 )
					b |= (OPCODE & 0xF);
				
				o.write(b);
				
				// PAYLOAD LENGTH
				b = payload.length - start;
				if( b > maxSendSize ) b = maxSendSize;
				if( b <= 125 ) o.write(b);
				else if( b <= 0xFFFF )
				{
					o.write(0x7E);
					o.write((b >> 8) & 0xFF);
					o.write(b & 0xFF);
				}
				else
				{
					o.write(0x7F);
					o.write(0);
					o.write(0);
					o.write(0);
					o.write(0);
					o.write((b >> 24) & 0xFF);
					o.write((b >> 16) & 0xFF);
					o.write((b >> 8) & 0xFF);
					o.write(b & 0xFF);
				}
				
				// PAYLOAD
				o.write(payload, start, b);
				this.connection.write(o.toByteArray());
				o.reset();
			}
		}
		catch(Exception e)
		{
			Manager.of(Logger.class).finest(Websocket.class, e);
			try { this.connection.close(); } catch(Exception x) { /* ignore */ }
		}
	}
}
