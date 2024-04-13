/*
 * Copyright c Aeonics srl and/or its respectful owner. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER
 *
 * This material is subject to the Aeonics Commercial License agreement.
 */
package aeonics.http.filter;

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
	
	public Class<? extends Filter.Request> entity() { return Type.class; }
	
	public Template<? extends Filter.Request> template()
	{
		return new Template<Type>(Type.class, OptionsMethodFilter.class, Filter.class)
			.creator(Type::new)
			.summary("OPTIONS method")
			.description("Returns an empty content for the OPTIONS request method.");
	}
}
