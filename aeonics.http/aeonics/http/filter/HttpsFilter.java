package aeonics.http.filter;

import java.util.function.Supplier;

import aeonics.data.Data;
import aeonics.entity.Message;
import aeonics.http.Filter;
import aeonics.template.Template;

public class HttpsFilter extends Filter
{
	private static class Type extends Filter.Request
	{
		public Data filter(Message request)
		{
			if( !request.connection().isSecure() )
			{
				return Data.map()
					.put("isHttpResponse", true)
					.put("code", 308)
					.put("headers", Data.map()
						.put("Location", "https://" + request.content().get("headers").asString("host") + request.content().asString("path"))
						.put("Strict-Transport-Security", "max-age=2592000; includeSubDomains")
					);
			}
			else
				return null;
		}
	}
	
	protected Class<? extends HttpsFilter.Type> defaultTarget() { return HttpsFilter.Type.class; }
	protected Supplier<? extends HttpsFilter.Type> defaultCreator() { return HttpsFilter.Type::new; }

	@Override
	public Template<? extends Filter.Type> template()
	{
		return super.template()
			.summary("Force HTTPS")
			.description("Forces a redirect to the same page in HTTPS with HSTS header. The redirection happens only if the 'http.forcehttps' global configuration is set.");
	}
}
