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
	
	public Class<? extends Filter.Request> entity() { return Type.class; }
	
	public Template<? extends Filter.Request> template()
	{
		return new Template<Type>(Type.class, HttpsFilter.class, Filter.class)
			.creator(Type::new)
			.summary("Force HTTPS")
			.description("Forces a redirect to the same page in HTTPS with HSTS header. The redirection happens only if the 'http.forcehttps' global configuration is set.");
	}
}
