package aeonics.http.filter;

import java.util.Map;
import java.util.function.Supplier;

import aeonics.data.Data;
import aeonics.entity.Message;
import aeonics.http.Filter;
import aeonics.template.Parameter;
import aeonics.template.Template;

public class HeadersFilter extends Filter
{
	private static class Type extends Filter.Response
	{
		public Data filter(Message request, Data response)
		{
			Data headers = response.get("headers");
			
			Data override = valueOf("headers");
			if( override.isMap() )
			{
				for( Map.Entry<String, Data> h : override.entrySet() )
				{
					if( h.getValue().isEmpty() ) headers.remove(h.getKey());
					else headers.put(h.getKey(), h.getValue().asString());
				}
			}
			return null;
		}
	}

	protected Class<? extends HeadersFilter.Type> defaultEntity() { return HeadersFilter.Type.class; }
	protected Supplier<? extends HeadersFilter.Type> defaultCreator() { return HeadersFilter.Type::new; }
	
	public Template<? extends Filter.Type> template()
	{
		return super.template()
			.summary("Headers")
			.description("Sets additional HTTP response headers. If the value of a header is empty or null, it will remove that response header instead.")
			.add(new Parameter("headers")
				.summary("Response headers")
				.description("This parameter should be a set of key-value pair headers to set. The default value is: {\"Strict-Transport-Security\": \"max-age=31536000; includeSubDomains; preload\",\"X-XSS-Protection\": \"1; mode=block\",\"X-Content-Type-Options\": \"nosniff\",\"Content-Security-Policy\": \"default-src 'self'; script-src 'self' 'unsafe-inline'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self'; frame-ancestors 'none'; block-all-mixed-content; upgrade-insecure-requests;\",\"X-Frame-Options\": \"DENY\",\"Referrer-Policy\": \"strict-origin-when-cross-origin\",\"Feature-Policy\": \"geolocation 'none'; microphone 'none'\"}")
				.defaultValue(Data.map()
					.put("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload")
					.put("X-XSS-Protection", "1; mode=block")
					.put("X-Content-Type-Options", "nosniff")
					.put("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self'; frame-ancestors 'none'; block-all-mixed-content; upgrade-insecure-requests;")
					.put("X-Frame-Options", "DENY")
					.put("Referrer-Policy", "strict-origin-when-cross-origin")
					.put("Feature-Policy", "geolocation 'none'; microphone 'none'")
				)
			);
	}
}
