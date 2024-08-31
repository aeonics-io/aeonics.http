package aeonics.http.filter;

import java.util.function.Supplier;

import aeonics.data.Data;
import aeonics.entity.Message;
import aeonics.http.Filter;
import aeonics.template.Template;

public class OptionsMethodFilter extends Filter
{
	private static class Type extends Filter.Request
	{
		public Data filter(Message request)
		{
			if( request.content().asString("method").equals("OPTIONS") )
			{
				return Data.map()
					.put("isHttpResponse", true)
					.put("code", 204);
			}
			else
				return null;
		}
	}
	
	protected Class<? extends OptionsMethodFilter.Type> defaultTarget() { return OptionsMethodFilter.Type.class; }
	protected Supplier<? extends OptionsMethodFilter.Type> defaultCreator() { return OptionsMethodFilter.Type::new; }

	@Override
	public Template<? extends Filter.Type> template()
	{
		return super.template()
			.summary("OPTIONS method")
			.description("Returns an empty content for the OPTIONS request method.");
	}
}
