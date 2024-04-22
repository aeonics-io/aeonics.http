/*
 * Copyright (c) Aeonics srl and/or its respectful owner. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER
 *
 * This material is subject to the Aeonics Commercial License agreement.
 */
package aeonics.http;

import aeonics.data.Data;
import aeonics.entity.Entity;
import aeonics.entity.Message;
import aeonics.template.Item;
import aeonics.util.StringUtils;

public abstract class Filter extends Item<Filter.Type>
{
	public static abstract class Type extends Entity
	{
		/**
		 * Filter the requests to perform pre-endpoint treatments.
		 * 
		 * @see Endpoint.Type#process(Message) for the response content and structure
		 * @param request the incoming request
		 * @return To abort the processing and send an error, throw an Exception. 
		 * 		To continue the filter chain, return null (the request object can sill be altered).
		 * 		To stop the filter chain and prevent processing, return the response to send directly.
		 */
		public abstract Data filter(Message request);
		
		/**
		 * Filter the requests to perform post-endpoint treatments.
		 * 
		 * @see Endpoint.Type#process(Message) for the response content and structure
		 * @param request the incoming request
		 * @param response the generated response
		 * @return To overwrite the response with an error, throw an Exception. 
		 * 		To continue the filter chain, return null (the response object can sill be altered).
		 * 		To stop the filter chain, return the response to send directly.
		 */
		public abstract Data filter(Message request, Data response);
		
		/**
		 * Hardcoded category to the {@link Filter} class
		 */
		public final String category() { return StringUtils.toLowerCase(Filter.class); }
	}
	
	protected Class<? extends Filter> category() { return Filter.class; }
	
	// =========================================
	//
	// FILTER TYPES
	//
	// =========================================
	
	public static abstract class Request extends Type
	{
		public Data filter(Message request, Data response) { return null; }
	}
	
	public static abstract class Response extends Type
	{
		public Data filter(Message request) { return null; }
	}
}
