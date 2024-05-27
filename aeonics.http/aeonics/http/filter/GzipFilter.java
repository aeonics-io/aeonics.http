/*
 * Copyright c Aeonics srl and/or its respectful owner. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER
 *
 * This material is subject to the Aeonics Commercial License agreement.
 */
package aeonics.http.filter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

import aeonics.data.Data;
import aeonics.entity.Message;
import aeonics.http.Filter;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.template.Template;

public class GzipFilter extends Filter
{
	private static class Type extends Filter.Response
	{
		public Data filter(Message request, Data response)
		{
			Data response_headers = response.get("headers");
			Data request_headers = request.content().get("headers");
			
			if( response_headers.containsKey("content-encoding") || response_headers.containsKey("Content-Encoding") )
				return null;
			
			String mime = "";
			if( response_headers.containsKey("content-type") ) mime = response_headers.asString("content-type");
			else if( response_headers.containsKey("Content-Type") ) mime = response_headers.asString("Content-Type");
			else mime = response.asString("mime");
			
			// we consider that image, audio and video are already compressed. So GZIP them would make it worse 
			if( mime.startsWith("image") || mime.startsWith("audio") || mime.startsWith("video") )
				return null;
			
			String accept = "";
			if( request_headers.containsKey("accept-encoding") ) accept = request_headers.asString("accept-encoding");
			else if( request_headers.containsKey("Accept-Encoding") ) accept = request_headers.asString("Accept-Encoding");
			
			// client does not support gzip
			if( !accept.contains("gzip") && !accept.contains("GZIP") )
				return null;
			
			byte[] body = response.asString("body").getBytes(StandardCharsets.ISO_8859_1);
			
			// small content might produce larger output
			if( body.length < 150 )
				return null;
			
			try ( ByteArrayOutputStream zipped = new ByteArrayOutputStream(body.length / 2) )
			{
				try ( GZIPOutputStream g = new GZIPOutputStream(zipped, body.length / 2) )
				{
					g.write(body);
					g.finish();
					response.put("body", new String(zipped.toByteArray(), StandardCharsets.ISO_8859_1));
					response_headers.put("Content-Encoding", "gzip");
				}
			}
			catch(Exception e)
			{
				Manager.of(Logger.class).fine(getClass(), e);
			}
			
			return null;
		}
	}
	
	protected Class<? extends GzipFilter.Type> defaultTarget() { return GzipFilter.Type.class; }
	protected Supplier<? extends GzipFilter.Type> defaultCreator() { return GzipFilter.Type::new; }

	@Override
	public Template<? extends Filter.Type> template()
	{
		return super.template()
			.summary("GZip")
			.description("Compresses the HTTP response if the client supports it. Media content (images, music, video) are not compressed because it is not worth it.");
	}
}
