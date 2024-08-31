package aeonics.http.filter;

import java.util.function.Supplier;

import aeonics.data.Data;
import aeonics.entity.Message;
import aeonics.http.Filter;
import aeonics.template.Parameter;
import aeonics.template.Template;

public class CorsFilter extends Filter
{
	private static class Type extends Filter.Response
	{
		public Data filter(Message request, Data response)
		{
			Data headers = response.get("headers");
			
			// Access-Control-Allow-Origin
			String acao = valueOf("origin").asString();
			if( !acao.isBlank() )
			{
				String host = request.content().get("headers").asString("host");
				
				if( acao.equals("*") ) headers.put("Access-Control-Allow-Origin", host).put("Vary", "Origin");
				else headers.put("Access-Control-Allow-Origin", acao);
			}
			
			if( request.content().asString("method").equals("OPTIONS") )
			{
				// Access-Control-Allow-Credentials
				boolean acac = valueOf("credentials").asBool();
				if( acac )
					headers.put("Access-Control-Allow-Credentials", "true");
				
				// Access-Control-Allow-Methods
				String acam = valueOf("methods").asString();
				if( !acam.isBlank() )
				{
					String method = request.content().get("headers").asString("access-control-request-method");
					
					if( acam.equals("*") ) headers.put("Access-Control-Allow-Methods", method);
					else headers.put("Access-Control-Allow-Methods", acam);
				}
				
				// Access-Control-Allow-Headers
				String acah = valueOf("headers").asString();
				if( !acah.isBlank() )
				{
					String header = request.content().get("headers").asString("access-control-request-headers");
					
					if( acah.equals("*") ) headers.put("Access-Control-Allow-Headers", header);
					else headers.put("Access-Control-Allow-Headers", acah);
				}
			}

			return null;
		}
	}

	protected Class<? extends CorsFilter.Type> defaultTarget() { return CorsFilter.Type.class; }
	protected Supplier<? extends CorsFilter.Type> defaultCreator() { return CorsFilter.Type::new; }

	@Override
	public Template<? extends Filter.Type> template()
	{
		return super.template()
			.summary("CORS")
			.description("Adds the CORS headers the HTTP response.")
			.add(new Parameter("credentials")
				.summary("Access-Control-Allow-Credentials")
				.description("The Access-Control-Allow-Credentials response header tells browsers whether the server allows cross-origin HTTP requests to include credentials. The value should be 'true' or 'false'.")
				.rule(Parameter.Rule.BOOLEAN)
				.format(Parameter.Format.BOOLEAN)
				.optional(true)
				.defaultValue(true)
				)
			.add(new Parameter("methods")
				.summary("Access-Control-Allow-Methods")
				.description("The Access-Control-Allow-Methods response header specifies one or more methods allowed when accessing a resource in response to a preflight request. The value shoud be a comma-delimited list of the allowed HTTP request methods. If the value '*' is used, the value is copied from the request 'Access-Control-Request-Method' header.")
				.format(Parameter.Format.TEXT)
				.optional(true)
				.defaultValue("*")
			// Access-Control-Request-Method
				)
			.add(new Parameter("origin")
				.summary("Access-Control-Allow-Origin")
				.description("The Access-Control-Allow-Origin response header indicates whether the response can be shared with requesting code from the given origin. Only a single origin can be specified. If the server supports clients from multiple origins, it must return the origin for the specific client making the request. If the value '*' is used, the value is copied from the request 'Host' header.")
				.format(Parameter.Format.TEXT)
				.optional(true)
				.defaultValue("*")
				)
			// Host
			// Vary: Origin
			.add(new Parameter("headers")
				.summary("Access-Control-Allow-Headers")
				.description("The Access-Control-Allow-Headers response header is used in response to a preflight request which includes the Access-Control-Request-Headers to indicate which HTTP headers can be used during the actual request. The value shoulld be a list of headers, separated by commas. If the special value '*' is used, the value is copied from the request 'Access-Control-Request-Headers' header.")
				.format(Parameter.Format.TEXT)
				.optional(true)
				.defaultValue("*")
				);
			// Access-Control-Request-Headers
	}
}
