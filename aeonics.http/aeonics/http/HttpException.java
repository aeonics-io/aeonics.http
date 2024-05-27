package aeonics.http;

import aeonics.data.Data;

public class HttpException extends RuntimeException
{
	public int code;
	public Data data;
	
	public HttpException(int code) { super(); this.code = code; this.data = Data.empty(); }
	public HttpException(int code, String message) { super(); this.code = code; this.data = Data.of(message); }
	public HttpException(int code, Data data) { super(); this.code = code; this.data = data; }
	public HttpException(int code, Throwable cause) { super(cause); this.code = code; this.data = Data.empty(); }

	@Override
	public String toString() { return getClass().getSimpleName() + ": " + code; }
}
