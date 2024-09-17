package aeonics.http;

import aeonics.data.Data;
import aeonics.manager.Logger;
import aeonics.manager.Manager;

public class HttpException extends RuntimeException
{
	public int code;
	public Data data;
	
	public HttpException(int code) { super(); this.code = code; this.data = Data.empty(); }
	public HttpException(int code, String message)
	{
		super();
		this.code = code;
		this.data = Data.map().put("error", Data.map().put("code", code).put("message", message));
	}
	public HttpException(int code, Data data) { super(); this.code = code; this.data = data; }
	public HttpException(int code, Exception cause)
	{
		super(cause);
		this.code = code;
		Data error = Data.map().put("code", code).put("message", cause.getMessage());
		int level = Manager.of(Logger.class).level();
		if( level <= Logger.FINE )
		{
			Data stack = Data.list();
			for( Throwable x = cause; x != null; x = x.getCause() )
			{
				for( StackTraceElement e : x.getStackTrace() )
				{
					if( level > Logger.ALL && ((e.getModuleName() != null && e.getModuleName().startsWith("java.")) 
							|| e.getClassName().startsWith("java.") 
							|| e.getClassName().startsWith("javax.") 
							|| e.getClassName().startsWith("jdk.") 
							|| e.getClassName().startsWith("sun.") 
							|| e.getClassName().startsWith("aeonics.")) )
						continue;
					stack.add(e.toString());
				}
				
				if( x.getCause() != null )
					stack.add("Caused by");
			}
			error.put("stack", stack);
		}
		this.data = Data.map().put("error", error);
	}

	@Override
	public String toString() { return getClass().getSimpleName() + ": " + code; }
}
