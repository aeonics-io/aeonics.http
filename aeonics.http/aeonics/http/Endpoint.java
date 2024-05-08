package aeonics.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import aeonics.data.Data;
import aeonics.entity.Entity;
import aeonics.entity.Message;
import aeonics.entity.Registry;
import aeonics.entity.Storage;
import aeonics.entity.security.User;
import aeonics.template.Item;
import aeonics.template.Parameter;
import aeonics.template.Relationship;
import aeonics.template.Template;
import aeonics.util.StringUtils;
import aeonics.util.Tuple;
import aeonics.util.Functions.BiConsumer;
import aeonics.util.Functions.BiFunction;
import aeonics.util.Functions.Function;
import aeonics.util.Functions.Supplier;
import aeonics.util.Functions.TriConsumer;
import aeonics.util.Functions.TriFunction;

/**
 * This item represents an HTTP endpoint that will produce a response to a request.
 */
public abstract class Endpoint extends Item<Endpoint.Type>
{
	/**
	 * This is the base endpoint.
	 */
	@SuppressWarnings("unchecked")
	public abstract static class Type extends Entity
	{
		/**
		 * Empty constructor
		 */
		public Type() { super(); }
		
		/**
		 * Accepted HTTP method
		 */
		private String method = "GET";
		
		/**
		 * Returns the HTTP method this endpoint accepts
		 * @return the HTTP method this endpoint accepts
		 */
		public String method() { return method; }
		
		/**
		 * Set the HTTP method this endpoint accepts
		 * @param value the HTTP method this endpoint accepts
		 * @return this
		 */
		public <T extends Endpoint.Type> T method(String value) { method = value; return (T) this; }
		
		/**
		 * The URL of this endpoint
		 */
		private String url = null;
		
		/**
		 * Returns the URL of this endpoint
		 * @return the URL of this endpoint
		 */
		public String url() { return url; }
		
		/**
		 * Sets the URL of this endpoint
		 * @param value the URL of this endpoint
		 * @return this
		 */
		public <T extends Endpoint.Type> T url(String value) { url = value; return (T) this; }
		
		/**
		 * Returns true if the provided URL matches this endpoint
		 * @param url the URL to compare
		 * @return true if the provided URL matches this endpoint
		 */
		public boolean matches(String url)
		{
			if( url == null || url.isEmpty() ) return false;
			return url.equals(url());
		}
		
		/**
		 * Processes the request and generates a response.
		 * 
		 * <p>The generated response can be any of the following:</p>
		 * <ul>
		 * <li>null: a 204 response is sent</li>
		 * <li>an exception: it is the same as if you throw it</li>
		 * <li>a scalar data type: it is sent as-is with an <code>'application/octet-stream'</code> mime type and a code 200</li>
		 * <li>a list or map data type: it is converted to its JSON representation and sent with an <code>'application/json'</code> mime type and a code 200</li>
		 * <li>a special response data map containing:
		 * 		<ul>
		 * 		<li>isHttpResponse [required]: true [required]</li>
		 * 		<li>code: the http status code to send. Default 200.</li>
		 * 		<li>status: the http status text. If not specified, it will be derived from the response code.</li>
		 * 		<li>mime: the mime type to send. Default 'application/octet-stream'.</li>
		 * 		<li>body: the content to send (if a data map or list, then its JSON representation is used). Default to "".</li>
		 * 		<li>headers: any http response headers to send as a data map.</li>
		 * 		</ul>
		 * </li>
		 * </ul>
		 * @param request the original message data
		 * @return the generated response
		 * @throws Throwable if anything happens, the exception will be wrapped in an {@link HttpException}
		 */
		public abstract Data process(Message request) throws Throwable;
		
		public String name() { return method() + " " + url(); }
		public <T extends Entity> T name(String value) { return (T) this; }
		
		/**
		 * Hardcoded category to the {@link Endpoint} class
		 */
		public final String category() { return StringUtils.toLowerCase(Endpoint.class); }
	}
	
	protected Class<? extends Endpoint> category() { return Endpoint.class; }
	
	// =========================================
	//
	// REST ENDPOINT
	//
	// =========================================
	
	/**
	 * This is the base REST endpoint.
	 * <p>There are two recommended ways to create your own endpoint.
	 * The first method allows to provide the data processing function and registers automatically the template in
	 * the factory and the instance in the registry:</p>
	 * <pre>
	 * Endpoint.Rest.Type endpoint = new Endpoint.Rest() { } // &lt;-- note the '{ }' to create a new anonymous class
	 *     
	 *     .template() // &lt;-- create the template and register it in the factory
	 *     
	 *     // add all your template documentation
	 *     .summary("Says hello to the world")
	 *     
	 *     .build() // &lt;-- create an instance of the entity and register it in the registry
	 *     .&lt;Rest.Type&gt;cast()
	 *     
	 *     // set the rest endpoint logic
	 *     .process(() -> Data.map().put("hello", "world")) // &lt;-- the endpoint logic
	 *     
	 *     // set the generic endpoint settings
	 *     .url("/hello")
	 *     .method("GET");
	 * </pre>
	 * 
	 * <p>If you need more control over the endpoint behavior such as private member variables or multiple
	 * methods, then you need to declare a custom entity end register it <b>before</b> calling the template method:</p>
	 * <pre>
	 * public static class Hello extends Endpoint.Rest.Type {
     *     private String who = "world";
	 *     private Data hello() { return Data.map().put("hello", who); }
	 *     public Data process() { return hello(); }
	 * }
	 * 
	 * Endpoint.Rest.Type endpoint = new Endpoint.Rest() { } // &lt;-- note the '{ }' to create a new anonymous class
	 *     
	 *     // register the custom entity before calling the template
	 *     .entity(Hello.class)
	 *     .creator(Hello::new)
	 *     
	 *     .template() // &lt;-- create the template and register it in the factory
	 *     
	 *     // add all your template documentation
	 *     .summary("Says hello to the world")
	 *     
	 *     .build() // &lt;-- create an instance of the entity and register it in the registry
	 *     
	 *     // set the generic endpoint settings
	 *     .url("/hello")
	 *     .method("GET");
	 * </pre>
	 */
	public abstract static class Rest extends Endpoint
	{
		/**
		 * This is the base REST endpoint type.
		 * <p>Unless overridden, all instances of this class are marked as internal in the constructor.</p>
		 */
		@SuppressWarnings("unchecked")
		public static class Type extends Endpoint.Type
		{
			public Type()
			{
				super();
				internal(true);
			}
			
			public <T extends Endpoint.Type> T url(String value) { super.url(validate(value)); return (T) this; }
			
			/**
			 * Used for wildcard path matching, or null if simple url
			 */
			private String wildcardUrl = null;
			/**
			 * Pattern to extract parameters from the url
			 */
			private Pattern urlParameterExtractor = null;
			/**
			 * Ordered list of url parameter names
			 */
			private List<String> urlParameterNames = null;
			/**
			 * Validates the url and prepares parameter extraction if needed
			 * @param url the endpoint url
			 * @return the normalized endpoint url
			 */
			private String validate(String url)
			{
				// make absolute url
				if( !url.startsWith("/") ) url = "/" + url;
				
				if( url.indexOf('{') >= 0 )
				{
					wildcardUrl = url.replaceAll("\\{[^\\}]+\\}", "*");
					urlParameterNames = new ArrayList<String>();
					
					StringBuilder sb = new StringBuilder();
					Matcher m = Pattern.compile("\\{([^\\}]+)\\}").matcher(url);
					int i = 0;
					while( m.find() )
					{
						sb.append(Pattern.quote(url.substring(i, m.start())));
						sb.append("(.*?)");
						i = m.end();
						urlParameterNames.add(m.group(1));
					}
					sb.append(Pattern.quote(url.substring(i)));
					urlParameterExtractor = Pattern.compile(sb.toString());
				}
				else
				{
					wildcardUrl = null;
					urlParameterExtractor = null;
					urlParameterNames = null;
				}
				
				return url;
			}
			
			/**
			 * Returns true if the provided URL matches this endpoint
			 * @param url the URL to compare
			 * @return true if the provided URL matches this endpoint
			 */
			public boolean matches(String url)
			{
				if( url == null || url.isEmpty() ) return false;
				if( wildcardUrl == null ) return url.equals(url());
				return StringUtils.simplePathMatches(wildcardUrl, url, urlWordWildcards, urlGlobalWildcards, urlNegators, urlWordDelimiters);
			}
			
			private final static char[] urlWordWildcards = new char[] { '*' };
			private final static char[] urlGlobalWildcards = new char[] { '#' };
			private final static char[] urlNegators = new char[] { '!' };
			private final static char[] urlWordDelimiters = new char[] { '/' };
			
			/**
			 * Handler in case of error
			 */
			private TriFunction<Data, User.Type, Throwable, Data> errorHandler = null;
			
			/**
			 * Sets an error handler for this endpoint. This is the opportunity to catch the error and return another response instead.
			 * <p>The handler has the following signature: <code>BiFunction&lt;Tuple&lt;Data, User.Type&gt;, Throwable, Data&gt;</code></p>
			 * <ol>
			 * <li>The first argument <code>Tuple&lt;Data, User.Type&gt;</code> represents the http request parameters and the authenticated user</li>
			 * <li>The second argument is the error thrown from the endpoint</li>
			 * <li>The handler should return a new response, or null to propagate the error in the response</li>
			 * </ol>
			 * <p>If the handler throws an exception, it has precedence over the endpoint error and will be sent as a response instead.</p>
			 * @param <T> this
			 * @param handler the error handler
			 * @return this
			 */
			public <T extends Endpoint.Rest.Type> T error(TriFunction<Data, User.Type, Throwable, Data> handler) { errorHandler = handler; return (T) this; }
			
			/**
			 * Sets an error handler for this endpoint.
			 * The consumer cannot return a value to override the response, but may throw another exception if needed.
			 * @see #error(BiFunction)
			 * @param <T> this
			 * @param handler the error handler
			 * @return this
			 */
			public <T extends Endpoint.Rest.Type> T error(TriConsumer<Data, User.Type, Throwable> handler) { return error((request, user, error) -> { handler.accept(request, user, error); return null; }); }
			
			/**
			 * Handler called before processing the request
			 */
			private BiFunction<Data, User.Type, Data> beforeHandler = null;
			
			/**
			 * Sets a handler to intercept the request before it is processed by this endpoint.
			 * <p>It is the place to perform precondition or security checks and alter the input parameters if needed.
			 * The handler has the following signature: <code>BiFunction&lt;Data, User.Type, Data&gt;</code></p>
			 * <ol>
			 * <li>The first argument represents the http request parameters</li>
			 * <li>The second argument is the authenticated user</li>
			 * <li>The handler may return a totally new request data object, or null to keep the original</li>
			 * </ol>
			 * <p>It is allowed for the handler to modify the original request data in-place and return null afterwards.
			 * If the handler throws an exception, it will be directly sent as a response without executing the endpoint at all and without 
			 * proceeding through the error handler.</p>
			 * @param <T> this
			 * @param handler the before processing handler
			 * @return this
			 */
			public <T extends Endpoint.Rest.Type> T before(BiFunction<Data, User.Type, Data> handler) { beforeHandler = handler; return (T) this; }
			
			/**
			 * Sets a handler to intercept the request before it is processed by this endpoint.
			 * The consumer cannot return a value to override the request, but may still modify it in-place or throw an exception if needed.
			 * @see #before(BiFunction)
			 * @param <T> this
			 * @param handler the before processing handler
			 * @return this
			 */
			public <T extends Endpoint.Rest.Type> T before(BiConsumer<Data, User.Type> handler) { return before((parameters, user) -> { handler.accept(parameters, user); return null; }); }
			
			/**
			 * Handler called after processing the request
			 */
			private BiFunction<Tuple<Data, User.Type>, Data, Data> afterHandler = null;
			
			/**
			 * Sets a handler to intercept the response after it has been generated by this endpoint.
			 * <p>It is the place to perform output filtering before sending the response.
			 * The handler has the following signature: <code>BiFunction&lt;Tuple&lt;Data, User.Type&gt;, Data, Data&gt;</code></p>
			 * <ol>
			 * <li>The first argument <code>Tuple&lt;Data, User.Type&gt;</code> represents the http request parameters and the authenticated user</li>
			 * <li>The second argument is the response generated by this endpoint</li>
			 * <li>The handler may return a totally new response data object, or null to keep the original</li>
			 * </ol>
			 * <p>It is allowed for the handler to modify the original response data in-place and return null afterwards.
			 * If the handler throws an exception, it will be directly sent as a response instead without proceeding through the error handler.</p>
			 * @param <T> this
			 * @param handler the after processing handler
			 * @return this
			 */
			public <T extends Endpoint.Rest.Type> T after(BiFunction<Tuple<Data, User.Type>, Data, Data> handler) { afterHandler = handler; return (T) this; }
			
			/**
			 * Sets a handler to intercept the response after it has been generated by this endpoint.
			 * The consumer cannot return a value to override the response, but may still modify it in-place or throw an exception if needed.
			 * @see #after(BiFunction)
			 * @param <T> this
			 * @param handler the after processing handler
			 * @return this
			 */
			public <T extends Endpoint.Rest.Type> T after(BiConsumer<Tuple<Data, User.Type>, Data> handler) { return after((request, response) -> { handler.accept(request, response); return null; }); }
			
			/**
			 * Processes the request and generates a response.
			 * <p>This method will collect the parameters and execute the <code>before</code>, <code>after</code> and <code>error</code>
			 * handlers as expected.</p>
			 * 
			 * <p>The expected input message request content shall contain:</p>
			 * <ul>
			 * <li>method: the http method</li>
			 * <li>path: the full path url</li>
			 * <li>get: decoded key-value pair query string parameters</li>
			 * <li>post: decoded key-value pair body parameters</li>
			 * <li>files: the key-value pair uploaded files with the parameter name as key and the following value
			 * 		<ul>
			 * 		<li>name: the uploaded file name</li>
			 * 		<li>mime: the uploaded file mime type</li>
			 * 		<li>content: the uploaded file content</li>
			 * 		</ul>
			 * </li>
			 * <li>body: the raw request body</li>
			 * <li>headers: decoded key-value pair headers</li>
			 * </ul>
			 * 
			 * <p>The generated response can be any of the following:</p>
			 * <ul>
			 * <li>null: a 204 response is sent</li>
			 * <li>an exception: it is the same as if you throw it</li>
			 * <li>a scalar data type: it is sent as-is with an <code>'application/octet-stream'</code> mime type and a code 200</li>
			 * <li>a list or map data type: it is converted to its JSON representation and sent with an <code>'application/json'</code> mime type and a code 200</li>
			 * <li>a special response data map containing:
			 * 		<ul>
			 * 		<li>isHttpResponse [required]: true [required]</li>
			 * 		<li>code: the http status code to send. Default 200.</li>
			 * 		<li>status: the http status text. If not specified, it will be derived from the response code.</li>
			 * 		<li>mime: the mime type to send. Default 'application/octet-stream'.</li>
			 * 		<li>body: the content to send (if a data map or list, then its JSON representation is used). Default to "".</li>
			 * 		<li>headers: any http response headers to send as a data map.</li>
			 * 		</ul>
			 * </li>
			 * </ul>
			 * @param request the original message data
			 * @return the generated response
			 * @throws Throwable if anything happens, the exception will be wrapped in an {@link HttpException}
			 */
			public final Data process(Message request) throws Throwable
			{
				Data params = collectAndValidateParameters(request);
				User.Type user = Registry.of(User.class).get(request.user());
				if( user == null ) user = User.ANONYMOUS;
				
				if( beforeHandler != null )
				{
					Data new_params = beforeHandler.apply(params, user);
					if( new_params != null ) params = new_params;
				}
				
				Data response = null;
				try { response = process(params, user, request); }
				catch(Throwable t)
				{
					if( errorHandler != null )
					{
						response = errorHandler.apply(params, user, t);
						if( response == null ) throw t;
						else return response;
					}
					else throw t;
				}
				
				if( afterHandler != null )
				{
					Data new_response = afterHandler.apply(Tuple.of(params, user), response);
					if( new_response != null ) response = new_response;
				}
				
				return response;
			}
			
			/**
			 * Process function with 3 parameters
			 */
			private TriFunction<Data, User.Type, Message, Data> processor1 = null;
			
			/**
			 * Sets the process function that will replace the {@link #process(Data, User.Type, Message)} method.
			 * @param <T> this
			 * @param processor the process function with 3 input parameters
			 * @return this
			 */
			public <T extends Endpoint.Rest.Type> T process(TriFunction<Data, User.Type, Message, Data> processor) { this.processor1 = processor; return (T) this; }
			
			/**
			 * Processes the request and generates a response.
			 * You may override this method if you only need the validated input parameters but also the original message data.
			 * @param params the validated input parameters
			 * @param user the associated user
			 * @param request the original message data
			 * @return the endpoint response
			 * @throws Throwable if anything happens, the exception will be wrapped in an {@link HttpException}
			 * @see #process(Message)
			 */
			public Data process(Data params, User.Type user, Message request) throws Throwable
			{
				if( processor1 != null ) return processor1.apply(params, user, request);
				else return process(params, user);
			}
			
			/**
			 * Process function with 2 parameters
			 */
			private BiFunction<Data, User.Type, Data> processor2 = null;
			
			/**
			 * Sets the process function that will replace the {@link #process(Data, User.Type)} method.
			 * @param <T> this
			 * @param processor the process function with 2 input parameters
			 * @return this
			 */
			public <T extends Endpoint.Rest.Type> T process(BiFunction<Data, User.Type, Data> processor) { this.processor2 = processor; return (T) this; }
			
			/**
			 * Processes the request and generates a response.
			 * You may override this method if you only need the validated input parameters.
			 * @param params the validated input parameters
			 * @param user the associated user
			 * @return the endpoint response
			 * @throws Throwable if anything happens, the exception will be wrapped in an {@link HttpException}
			 * @see #process(Message)
			 */
			public Data process(Data params, User.Type user) throws Throwable
			{
				if( processor2 != null ) return processor2.apply(params);
				else return process(params);
			}
			
			/**
			 * Process function with 1 parameter
			 */
			private Function<Data, Data> processor3 = null;
			
			/**
			 * Sets the process function that will replace the {@link #process(Data)} method.
			 * @param <T> this
			 * @param processor the process function with 1 input parameter
			 * @return this
			 */
			public <T extends Endpoint.Rest.Type> T process(Function<Data, Data> processor) { this.processor3 = processor; return (T) this; }
			
			/**
			 * Processes the request and generates a response.
			 * You may override this method if you only need the validated input parameters.
			 * @param params the validated input parameters
			 * @return the endpoint response
			 * @throws Throwable if anything happens, the exception will be wrapped in an {@link HttpException}
			 * @see #process(Message)
			 */
			public Data process(Data params) throws Throwable
			{
				if( processor3 != null ) return processor3.apply(params);
				else return process();
			}
			
			/**
			 * Process function without parameters
			 */
			private Supplier<Data> processor4 = null;
			
			/**
			 * Sets the process function that will replace the {@link #process()} method.
			 * @param <T> this
			 * @param processor the process function without input parameters
			 * @return this
			 */
			public <T extends Endpoint.Rest.Type> T process(Supplier<Data> processor) { this.processor4 = processor; return (T) this; }
			
			/**
			 * Processes the request and generates a response.
			 * You may override this method if you do not need any input parameters.
			 * @return the endpoint response
			 * @throws Throwable if anything happens, the exception will be wrapped in an {@link HttpException}
			 * @see #process(Message)
			 */
			public Data process() throws Throwable
			{
				if( processor4 != null ) return processor4.get();
				else return null;
			}
			
			/**
			 * Collects input parameters based on the original message data.
			 * All parameters are validated according to this instance's {@link #parameters()}.
			 * <p>The parameters are collected regardless of their provenance in the original request.
			 * The order of precedence is first <code>GET</code> parameters, then <code>POST</code> parameters,
			 * then <code>FILES</code> parameters.</p>
			 * <p>If the parameter is not found in the request, the configured default value is used.</p>
			 * 
			 * @param request the original message data
			 * @return a key-value pair of all parameters and their value
			 * @throws Throwable if any parameter validation fails or if the input message is malformed
			 */
			public Data collectAndValidateParameters(Message request) throws Throwable
			{
				// add url parameters as GET parameters
				if( wildcardUrl != null )
				{
					Matcher m = urlParameterExtractor.matcher(request.content().asString("path"));
					if( m.matches() )
					{
						Data get = request.content().get("get");
						for( int i = 0; i < urlParameterNames.size(); i++ )
							get.put(urlParameterNames.get(i), m.group(i+1));
					}
				}
				
				Data params = Data.map();
				for( Tuple<Data, Parameter> p : parameters().values() )
				{
					Data value = request.content().get("get").get(p.b.name());
					if( value.isNull() )
						value = request.content().get("post").get(p.b.name());
					if( value.isNull() )
						value = request.content().get("files").get(p.b.name());
					if( value.isNull() )
						value = p.b.defaultValue();
					
					value = p.b.resolve(value, request.content());
					if( !p.b.validate(value) )
						throw new IllegalArgumentException("Parameter validation failed for " + p.b.name());
					params.put(p.b.name(), value);
				}
				return params;
			}
		}
	
		protected Class<? extends Rest.Type> defaultTarget() { return Rest.Type.class; }
		protected java.util.function.Supplier<? extends Rest.Type> defaultCreator() { return Rest.Type::new; }
		
		public Template<? extends Endpoint.Type> template()
		{
			return super.template()
				.enforceParameterValidation(false)
				.removeParameter("name");
		}
	}
	
	// =========================================
	//
	// FILE ENDPOINT
	//
	// =========================================
	
	public static class File extends Endpoint
	{
		public static class Type extends Endpoint.Type
		{
			public Storage.Type store()
			{
				for( Tuple<Entity, Data> r : relations("storage") )
					return (Storage.Type) r.a;
				return null;
			}
			
			public boolean matches(String url)
			{
				String filter = valueOf("filter").asString();
				if( filter != null && !url.startsWith(filter) ) return false;
				
				Storage.Type store = store();
				if( store == null ) return false;
				
				if( url.isBlank() || url.endsWith("/") ) url += "index.html";

				return store.containsEntry(valueOf("path").asString() + "/" + url.substring(filter.length()))
					|| store.containsEntry(valueOf("path").asString() + "/" + url.substring(filter.length()) + ".html")
					|| store.containsEntry(valueOf("path").asString() + "/" + url.substring(filter.length()) + "/index.html");
			}
			
			public Data process(Message request) throws Throwable
			{
				String filter = valueOf("filter").asString();
				Storage.Type store = store();
				if( store == null ) throw new HttpException(404);
				
				String path = request.content().asString("path");
				if( path.isBlank() || path.endsWith("/") ) path += "index.html";
				path = valueOf("path").asString() + "/" + path.substring(filter.length());
				if( !store.containsEntry(path) )
				{
					if( store.containsEntry(path + ".html") ) path += ".html";
					else if( store.containsPath(path) && store.containsEntry(path + "/index.html") )
					{
						// since /foo/bar is a directory, redirect /foo/bar?a=42 to /foo/bar/?a=42 (add trailing /)
						String url = (request.metadata().asBool("tls") ? "https://" : "http://") + 
							request.content().get("headers").asString("host") + 
							request.content().asString("path");
						int qm = url.indexOf('?');
						if( qm >= 0 ) url = url.substring(0, qm) + "/" + url.substring(qm);
						else url += "/";
						
						return Data.map()
							.put("isHttpResponse", true)
							.put("code", 301)
							.put("headers", Data.map().put("Location", url));
					}
				}
				
				byte[] file = store.get(path);
				if( file == null ) throw new HttpException(404);
				
				return Data.map()
					.put("isHttpResponse", true)
					.put("code", 200)
					.put("body", new String(file, StandardCharsets.ISO_8859_1))
					.put("mime", mime(path, file));
			}
			
			/**
			 * Returns the mime type guess based on the file extension.
			 * @return the mime type
			 */
			protected String mime(String path, byte[] file)
			{
				int i = path.lastIndexOf('.');
				String mime = mimes.get(path.substring(i < 0 ? 0 : i+1));
				if( mime == null ) return "application/octet-stream";
				return mime;
			}
			
			private static Map<String, String> mimes = new HashMap<String, String>();
			
			protected static void set(final String mime, final String... ext)
			{
				if( ext == null || mime == null ) return;
				for( String e : ext )
					mimes.put(e, mime);
			}
			
			static
			{
				set("application/andrew-inset","ez");
				set("application/atom+xml","atom");
				set("application/atomcat+xml","atomcat");
				set("application/atomsvc+xml","atomsvc");
				set("application/ccxml+xml","ccxml");
				set("application/davmount+xml","davmount");
				set("application/ecmascript","ecma");
				set("application/font-tdpfr","pfr");
				set("application/hyperstudio","stk");
				set("application/javascript","js");
				set("application/json","json");
				set("application/lost+xml","lostxml");
				set("application/mac-binhex40","hqx");
				set("application/mac-compactpro","cpt");
				set("application/marc","mrc");
				set("application/mathematica","ma","nb","mb");
				set("application/mathml+xml","mathml");
				set("application/mbox","mbox");
				set("application/mediaservercontrol+xml","mscml");
				set("application/mp4","mp4s");
				set("application/msword","doc","dot");
				set("application/mxf","mxf");
				set("application/octet-stream","bin","dms","lha","lzh","class","so","iso","dmg","dist","distz","pkg","bpk","dump","elc");
				set("application/oda","oda");
				set("application/ogg","ogx");
				set("application/patch-ops-error+xml","xer");
				set("application/pdf","pdf");
				set("application/pgp-encrypted","pgp");
				set("application/pgp-signature","asc","sig");
				set("application/pics-rules","prf");
				set("application/pkcs10","p10");
				set("application/pkcs7-mime","p7m","p7c");
				set("application/pkcs7-signature","p7s");
				set("application/pkix-cert","cer");
				set("application/pkix-crl","crl");
				set("application/pkix-pkipath","pkipath");
				set("application/pkixcmp","pki");
				set("application/pls+xml","pls");
				set("application/postscript","ai","eps","ps");
				set("application/prs.cww","cww");
				set("application/rdf+xml","rdf");
				set("application/reginfo+xml","rif");
				set("application/relax-ng-compact-syntax","rnc");
				set("application/resource-lists+xml","rl");
				set("application/resource-lists-diff+xml","rld");
				set("application/rls-services+xml","rs");
				set("application/rsd+xml","rsd");
				set("application/rss+xml","rss");
				set("application/rtf","rtf");
				set("application/sbml+xml","sbml");
				set("application/scvp-cv-request","scq");
				set("application/scvp-cv-response","scs");
				set("application/scvp-vp-request","spq");
				set("application/scvp-vp-response","spp");
				set("application/sdp","sdp");
				set("application/set-payment-initiation","setpay");
				set("application/set-registration-initiation","setreg");
				set("application/shf+xml","shf");
				set("application/smil+xml","smi","smil");
				set("application/sparql-query","rq");
				set("application/sparql-results+xml","srx");
				set("application/srgs","gram");
				set("application/srgs+xml","grxml");
				set("application/ssml+xml","ssml");
				set("application/vnd.3gpp.pic-bw-large","plb");
				set("application/vnd.3gpp.pic-bw-small","psb");
				set("application/vnd.3gpp.pic-bw-var","pvb");
				set("application/vnd.3gpp2.tcap","tcap");
				set("application/vnd.3m.post-it-notes","pwn");
				set("application/vnd.accpac.simply.aso","aso");
				set("application/vnd.accpac.simply.imp","imp");
				set("application/vnd.acucobol","acu");
				set("application/vnd.acucorp","atc","acutc");
				set("application/vnd.adobe.xdp+xml","xdp");
				set("application/vnd.adobe.xfdf","xfdf");
				set("application/vnd.americandynamics.acc","acc");
				set("application/vnd.amiga.ami","ami");
				set("application/vnd.anser-web-certificate-issue-initiation","cii");
				set("application/vnd.anser-web-funds-transfer-initiation","fti");
				set("application/vnd.antix.game-component","atx");
				set("application/vnd.apple.installer+xml","mpkg");
				set("application/vnd.arastra.swi","swi");
				set("application/vnd.audiograph","aep");
				set("application/vnd.blueice.multipass","mpm");
				set("application/vnd.bmi","bmi");
				set("application/vnd.businessobjects","rep");
				set("application/vnd.chemdraw+xml","cdxml");
				set("application/vnd.chipnuts.karaoke-mmd","mmd");
				set("application/vnd.cinderella","cdy");
				set("application/vnd.claymore","cla");
				set("application/vnd.clonk.c4group","c4g","c4d","c4f","c4p","c4u");
				set("application/vnd.commonspace","csp","cst");
				set("application/vnd.contact.cmsg","cdbcmsg");
				set("application/vnd.cosmocaller","cmc");
				set("application/vnd.crick.clicker","clkx");
				set("application/vnd.crick.clicker.keyboard","clkk");
				set("application/vnd.crick.clicker.palette","clkp");
				set("application/vnd.crick.clicker.template","clkt");
				set("application/vnd.crick.clicker.wordbank","clkw");
				set("application/vnd.criticaltools.wbs+xml","wbs");
				set("application/vnd.ctc-posml","pml");
				set("application/vnd.cups-ppd","ppd");
				set("application/vnd.curl","curl");
				set("application/vnd.data-vision.rdz","rdz");
				set("application/vnd.denovo.fcselayout-link","fe_launch");
				set("application/vnd.dna","dna");
				set("application/vnd.dolby.mlp","mlp");
				set("application/vnd.dpgraph","dpg");
				set("application/vnd.dreamfactory","dfac");
				set("application/vnd.ecowin.chart","mag");
				set("application/vnd.enliven","nml");
				set("application/vnd.epson.esf","esf");
				set("application/vnd.epson.msf","msf");
				set("application/vnd.epson.quickanime","qam");
				set("application/vnd.epson.salt","slt");
				set("application/vnd.epson.ssf","ssf");
				set("application/vnd.eszigno3+xml","es3","et3");
				set("application/vnd.ezpix-album","ez2");
				set("application/vnd.ezpix-package","ez3");
				set("application/vnd.fdf","fdf");
				set("application/vnd.flographit","gph");
				set("application/vnd.fluxtime.clip","ftc");
				set("application/vnd.framemaker","fm","frame","maker");
				set("application/vnd.frogans.fnc","fnc");
				set("application/vnd.frogans.ltf","ltf");
				set("application/vnd.fsc.weblaunch","fsc");
				set("application/vnd.fujitsu.oasys","oas");
				set("application/vnd.fujitsu.oasys2","oa2");
				set("application/vnd.fujitsu.oasys3","oa3");
				set("application/vnd.fujitsu.oasysgp","fg5");
				set("application/vnd.fujitsu.oasysprs","bh2");
				set("application/vnd.fujixerox.ddd","ddd");
				set("application/vnd.fujixerox.docuworks","xdw");
				set("application/vnd.fujixerox.docuworks.binder","xbd");
				set("application/vnd.fuzzysheet","fzs");
				set("application/vnd.genomatix.tuxedo","txd");
				set("application/vnd.gmx","gmx");
				set("application/vnd.google-earth.kml+xml","kml");
				set("application/vnd.google-earth.kmz","kmz");
				set("application/vnd.grafeq","gqf","gqs");
				set("application/vnd.groove-account","gac");
				set("application/vnd.groove-help","ghf");
				set("application/vnd.groove-identity-message","gim");
				set("application/vnd.groove-injector","grv");
				set("application/vnd.groove-tool-message","gtm");
				set("application/vnd.groove-tool-template","tpl");
				set("application/vnd.groove-vcard","vcg");
				set("application/vnd.handheld-entertainment+xml","zmm");
				set("application/vnd.hbci","hbci");
				set("application/vnd.hhe.lesson-player","les");
				set("application/vnd.hp-hpgl","hpgl");
				set("application/vnd.hp-hpid","hpid");
				set("application/vnd.hp-hps","hps");
				set("application/vnd.hp-jlyt","jlt");
				set("application/vnd.hp-pcl","pcl");
				set("application/vnd.hp-pclxl","pclxl");
				set("application/vnd.hydrostatix.sof-data","sfd-hdstx");
				set("application/vnd.hzn-3d-crossword","x3d");
				set("application/vnd.ibm.minipay","mpy");
				set("application/vnd.ibm.modcap","afp","listafp","list3820");
				set("application/vnd.ibm.rights-management","irm");
				set("application/vnd.ibm.secure-container","sc");
				set("application/vnd.iccprofile","icc","icm");
				set("application/vnd.igloader","igl");
				set("application/vnd.immervision-ivp","ivp");
				set("application/vnd.immervision-ivu","ivu");
				set("application/vnd.intercon.formnet","xpw","xpx");
				set("application/vnd.intu.qbo","qbo");
				set("application/vnd.intu.qfx","qfx");
				set("application/vnd.ipunplugged.rcprofile","rcprofile");
				set("application/vnd.irepository.package+xml","irp");
				set("application/vnd.is-xpr","xpr");
				set("application/vnd.jam","jam");
				set("application/vnd.jcp.javame.midlet-rms","rms");
				set("application/vnd.jisp","jisp");
				set("application/vnd.joost.joda-archive","joda");
				set("application/vnd.kahootz","ktz","ktr");
				set("application/vnd.kde.karbon","karbon");
				set("application/vnd.kde.kchart","chrt");
				set("application/vnd.kde.kformula","kfo");
				set("application/vnd.kde.kivio","flw");
				set("application/vnd.kde.kontour","kon");
				set("application/vnd.kde.kpresenter","kpr","kpt");
				set("application/vnd.kde.kspread","ksp");
				set("application/vnd.kde.kword","kwd","kwt");
				set("application/vnd.kenameaapp","htke");
				set("application/vnd.kidspiration","kia");
				set("application/vnd.kinar","kne","knp");
				set("application/vnd.koan","skp","skd","skt","skm");
				set("application/vnd.kodak-descriptor","sse");
				set("application/vnd.llamagraphics.life-balance.desktop","lbd");
				set("application/vnd.llamagraphics.life-balance.exchange+xml","lbe");
				set("application/vnd.lotus-1-2-3","123");
				set("application/vnd.lotus-approach","apr");
				set("application/vnd.lotus-freelance","pre");
				set("application/vnd.lotus-notes","nsf");
				set("application/vnd.lotus-organizer","org");
				set("application/vnd.lotus-screencam","scm");
				set("application/vnd.lotus-wordpro","lwp");
				set("application/vnd.macports.portpkg","portpkg");
				set("application/vnd.mcd","mcd");
				set("application/vnd.medcalcdata","mc1");
				set("application/vnd.mediastation.cdkey","cdkey");
				set("application/vnd.mfer","mwf");
				set("application/vnd.mfmp","mfm");
				set("application/vnd.micrografx.flo","flo");
				set("application/vnd.micrografx.igx","igx");
				set("application/vnd.mif","mif");
				set("application/vnd.mobius.daf","daf");
				set("application/vnd.mobius.dis","dis");
				set("application/vnd.mobius.mbk","mbk");
				set("application/vnd.mobius.mqy","mqy");
				set("application/vnd.mobius.msl","msl");
				set("application/vnd.mobius.plc","plc");
				set("application/vnd.mobius.txf","txf");
				set("application/vnd.mophun.application","mpn");
				set("application/vnd.mophun.certificate","mpc");
				set("application/vnd.mozilla.xul+xml","xul");
				set("application/vnd.ms-artgalry","cil");
				set("application/vnd.ms-asf","asf");
				set("application/vnd.ms-cab-compressed","cab");
				set("application/vnd.ms-excel","xls","xlm","xla","xlc","xlt","xlw");
				set("application/vnd.ms-fontobject","eot");
				set("application/vnd.ms-htmlhelp","chm");
				set("application/vnd.ms-ims","ims");
				set("application/vnd.ms-lrm","lrm");
				set("application/vnd.ms-powerpoint","ppt","pps","pot");
				set("application/vnd.ms-project","mpp","mpt");
				set("application/vnd.ms-works","wps","wks","wcm","wdb");
				set("application/vnd.ms-wpl","wpl");
				set("application/vnd.ms-xpsdocument","xps");
				set("application/vnd.mseq","mseq");
				set("application/vnd.musician","mus");
				set("application/vnd.muvee.style","msty");
				set("application/vnd.neurolanguage.nlu","nlu");
				set("application/vnd.noblenet-directory","nnd");
				set("application/vnd.noblenet-sealer","nns");
				set("application/vnd.noblenet-web","nnw");
				set("application/vnd.nokia.n-gage.data","ngdat");
				set("application/vnd.nokia.n-gage.symbian.install","n-gage");
				set("application/vnd.nokia.radio-preset","rpst");
				set("application/vnd.nokia.radio-presets","rpss");
				set("application/vnd.novadigm.edm","edm");
				set("application/vnd.novadigm.edx","edx");
				set("application/vnd.novadigm.ext","ext");
				set("application/vnd.oasis.opendocument.chart","odc");
				set("application/vnd.oasis.opendocument.chart-template","otc");
				set("application/vnd.oasis.opendocument.formula","odf");
				set("application/vnd.oasis.opendocument.formula-template","otf");
				set("application/vnd.oasis.opendocument.graphics","odg");
				set("application/vnd.oasis.opendocument.graphics-template","otg");
				set("application/vnd.oasis.opendocument.image","odi");
				set("application/vnd.oasis.opendocument.image-template","oti");
				set("application/vnd.oasis.opendocument.presentation","odp");
				set("application/vnd.oasis.opendocument.presentation-template","otp");
				set("application/vnd.oasis.opendocument.spreadsheet","ods");
				set("application/vnd.oasis.opendocument.spreadsheet-template","ots");
				set("application/vnd.oasis.opendocument.text","odt");
				set("application/vnd.oasis.opendocument.text-master","otm");
				set("application/vnd.oasis.opendocument.text-template","ott");
				set("application/vnd.oasis.opendocument.text-web","oth");
				set("application/vnd.olpc-sugar","xo");
				set("application/vnd.oma.dd2+xml","dd2");
				set("application/vnd.openofficeorg.extension","oxt");
				set("application/vnd.osgi.dp","dp");
				set("application/vnd.palm","prc","pdb","pqa","oprc");
				set("application/vnd.pg.format","str");
				set("application/vnd.pg.osasli","ei6");
				set("application/vnd.picsel","efif");
				set("application/vnd.pocketlearn","plf");
				set("application/vnd.powerbuilder6","pbd");
				set("application/vnd.previewsystems.box","box");
				set("application/vnd.proteus.magazine","mgz");
				set("application/vnd.publishare-delta-tree","qps");
				set("application/vnd.pvi.ptid1","ptid");
				set("application/vnd.quark.quarkxpress","qxd","qxt","qwd","qwt","qxl","qxb");
				set("application/vnd.recordare.musicxml","mxl");
				set("application/vnd.rn-realmedia","rm");
				set("application/vnd.route66.link66+xml","link66");
				set("application/vnd.seemail","see");
				set("application/vnd.sema","sema");
				set("application/vnd.semd","semd");
				set("application/vnd.semf","semf");
				set("application/vnd.shana.informed.formdata","ifm");
				set("application/vnd.shana.informed.formtemplate","itp");
				set("application/vnd.shana.informed.interchange","iif");
				set("application/vnd.shana.informed.package","ipk");
				set("application/vnd.simtech-mindmapper","twd","twds");
				set("application/vnd.smaf","mmf");
				set("application/vnd.solent.sdkm+xml","sdkm","sdkd");
				set("application/vnd.spotfire.dxp","dxp");
				set("application/vnd.spotfire.sfs","sfs");
				set("application/vnd.sus-calendar","sus","susp");
				set("application/vnd.svd","svd");
				set("application/vnd.syncml+xml","xsm");
				set("application/vnd.syncml.dm+wbxml","bdm");
				set("application/vnd.syncml.dm+xml","xdm");
				set("application/vnd.tao.intent-module-archive","tao");
				set("application/vnd.tmobile-livetv","tmo");
				set("application/vnd.trid.tpt","tpt");
				set("application/vnd.triscape.mxs","mxs");
				set("application/vnd.trueapp","tra");
				set("application/vnd.ufdl","ufd","ufdl");
				set("application/vnd.uiq.theme","utz");
				set("application/vnd.umajin","umj");
				set("application/vnd.unity","unityweb");
				set("application/vnd.uoml+xml","uoml");
				set("application/vnd.vcx","vcx");
				set("application/vnd.visio","vsd","vst","vss","vsw");
				set("application/vnd.visionary","vis");
				set("application/vnd.vsf","vsf");
				set("application/vnd.wap.wbxml","wbxml");
				set("application/vnd.wap.wmlc","wmlc");
				set("application/vnd.wap.wmlscriptc","wmlsc");
				set("application/vnd.webturbo","wtb");
				set("application/vnd.wordperfect","wpd");
				set("application/vnd.wqd","wqd");
				set("application/vnd.wt.stf","stf");
				set("application/vnd.xara","xar");
				set("application/vnd.xfdl","xfdl");
				set("application/vnd.yamaha.hv-dic","hvd");
				set("application/vnd.yamaha.hv-script","hvs");
				set("application/vnd.yamaha.hv-voice","hvp");
				set("application/vnd.yamaha.smaf-audio","saf");
				set("application/vnd.yamaha.smaf-phrase","spf");
				set("application/vnd.yellowriver-custom-menu","cmp");
				set("application/vnd.zzazz.deck+xml","zaz");
				set("application/voicexml+xml","vxml");
				set("application/winhlp","hlp");
				set("application/wsdl+xml","wsdl");
				set("application/wspolicy+xml","wspolicy");
				set("application/x-ace-compressed","ace");
				set("application/x-bcpio","bcpio");
				set("application/x-bittorrent","torrent");
				set("application/x-bzip","bz");
				set("application/x-bzip2","bz2","boz");
				set("application/x-cdlink","vcd");
				set("application/x-chat","chat");
				set("application/x-chess-pgn","pgn");
				set("application/x-cpio","cpio");
				set("application/x-csh","csh");
				set("application/x-director","dcr","dir","dxr","fgd");
				set("application/x-dvi","dvi");
				set("application/x-futuresplash","spl");
				set("application/x-gtar","gtar");
				set("application/x-hdf","hdf");
				set("application/x-latex","latex");
				set("application/x-ms-wmd","wmd");
				set("application/x-ms-wmz","wmz");
				set("application/x-msaccess","mdb");
				set("application/x-msbinder","obd");
				set("application/x-mscardfile","crd");
				set("application/x-msclip","clp");
				set("application/x-msdownload","exe","dll","com","bat","msi");
				set("application/x-msmediaview","mvb","m13","m14");
				set("application/x-msmetafile","wmf");
				set("application/x-msmoney","mny");
				set("application/x-mspublisher","pub");
				set("application/x-msschedule","scd");
				set("application/x-msterminal","trm");
				set("application/x-mswrite","wri");
				set("application/x-netcdf","nc","cdf");
				set("application/x-pkcs12","p12","pfx");
				set("application/x-pkcs7-certificates","p7b","spc");
				set("application/x-pkcs7-certreqresp","p7r");
				set("application/x-rar-compressed","rar");
				set("application/x-sh","sh");
				set("application/x-shar","shar");
				set("application/x-shockwave-flash","swf");
				set("application/x-stuffit","sit");
				set("application/x-stuffitx","sitx");
				set("application/x-sv4cpio","sv4cpio");
				set("application/x-sv4crc","sv4crc");
				set("application/x-tar","tar");
				set("application/x-tcl","tcl");
				set("application/x-tex","tex");
				set("application/x-texinfo","texinfo","texi");
				set("application/x-ustar","ustar");
				set("application/x-wais-source","src");
				set("application/x-x509-ca-cert","der","crt");
				set("application/xenc+xml","xenc");
				set("application/xhtml+xml","xhtml","xht");
				set("application/xml","xml","xsl");
				set("application/xml-dtd","dtd");
				set("application/xop+xml","xop");
				set("application/xslt+xml","xslt");
				set("application/xspf+xml","xspf");
				set("application/xv+xml","mxml","xhvml","xvml","xvm");
				set("application/zip","zip");
				set("audio/basic","au","snd");
				set("audio/midi","mid","midi","kar","rmi");
				set("audio/mp4","mp4a");
				set("audio/mpeg","mpga","mp2","mp2a","mp3","m2a","m3a");
				set("audio/ogg","oga","ogg","spx");
				set("audio/vnd.digital-winds","eol");
				set("audio/vnd.dts","dts");
				set("audio/vnd.dts.hd","dtshd");
				set("audio/vnd.lucent.voice","lvp");
				set("audio/vnd.ms-playready.media.pya","pya");
				set("audio/vnd.nuera.ecelp4800","ecelp4800");
				set("audio/vnd.nuera.ecelp7470","ecelp7470");
				set("audio/vnd.nuera.ecelp9600","ecelp9600");
				set("audio/wav","wav");
				set("audio/x-aiff","aif","aiff","aifc");
				set("audio/x-mpegurl","m3u");
				set("audio/x-ms-wax","wax");
				set("audio/x-ms-wma","wma");
				set("audio/x-pn-realaudio","ram","ra");
				set("audio/x-pn-realaudio-plugin","rmp");
				set("audio/x-wav","wav");
				set("chemical/x-cdx","cdx");
				set("chemical/x-cif","cif");
				set("chemical/x-cmdf","cmdf");
				set("chemical/x-cml","cml");
				set("chemical/x-csml","csml");
				set("chemical/x-pdb","pdb");
				set("chemical/x-xyz","xyz");
				set("image/bmp","bmp");
				set("image/cgm","cgm");
				set("image/g3fax","g3");
				set("image/gif","gif");
				set("image/ief","ief");
				set("image/jpeg","jpeg","jpg","jpe");
				set("image/png","png");
				set("image/prs.btif","btif");
				set("image/svg+xml","svg","svgz");
				set("image/tiff","tiff","tif");
				set("image/vnd.adobe.photoshop","psd");
				set("image/vnd.djvu","djvu","djv");
				set("image/vnd.dwg","dwg");
				set("image/vnd.dxf","dxf");
				set("image/vnd.fastbidsheet","fbs");
				set("image/vnd.fpx","fpx");
				set("image/vnd.fst","fst");
				set("image/vnd.fujixerox.edmics-mmr","mmr");
				set("image/vnd.fujixerox.edmics-rlc","rlc");
				set("image/vnd.ms-modi","mdi");
				set("image/vnd.net-fpx","npx");
				set("image/vnd.wap.wbmp","wbmp");
				set("image/vnd.xiff","xif");
				set("image/x-cmu-raster","ras");
				set("image/x-cmx","cmx");
				set("image/x-icon","ico");
				set("image/x-pcx","pcx");
				set("image/x-pict","pic","pct");
				set("image/x-portable-anymap","pnm");
				set("image/x-portable-bitmap","pbm");
				set("image/x-portable-graymap","pgm");
				set("image/x-portable-pixmap","ppm");
				set("image/x-rgb","rgb");
				set("image/x-xbitmap","xbm");
				set("image/x-xpixmap","xpm");
				set("image/x-xwindowdump","xwd");
				set("message/rfc822","eml","mime");
				set("model/iges","igs","iges");
				set("model/mesh","msh","mesh","silo");
				set("model/vnd.dwf","dwf");
				set("model/vnd.gdl","gdl");
				set("model/vnd.gtw","gtw");
				set("model/vnd.mts","mts");
				set("model/vnd.vtu","vtu");
				set("model/vrml","wrl","vrml");
				set("text/calendar","ics","ifb");
				set("text/css","css");
				set("text/csv","csv");
				set("text/html","html","htm");
				set("text/plain","txt","text","conf","def","list","log","in");
				set("text/prs.lines.tag","dsc");
				set("text/richtext","rtx");
				set("text/sgml","sgml","sgm");
				set("text/tab-separated-values","tsv");
				set("text/troff","t","tr","roff","man","me","ms");
				set("text/uri-list","uri","uris","urls");
				set("text/vnd.fly","fly");
				set("text/vnd.fmi.flexstor","flx");
				set("text/vnd.graphviz","gv");
				set("text/vnd.in3d.3dml","3dml");
				set("text/vnd.in3d.spot","spot");
				set("text/vnd.sun.j2me.app-descriptor","jad");
				set("text/vnd.wap.wml","wml");
				set("text/vnd.wap.wmlscript","wmls");
				set("text/x-asm","s","asm");
				set("text/x-c","c","cc","cxx","cpp","h","hh","dic");
				set("text/x-fortran","f","for","f77","f90");
				set("text/x-pascal","p","pas");
				set("text/x-java-source","java");
				set("text/x-setext","etx");
				set("text/x-uuencode","uu");
				set("text/x-vcalendar","vcs");
				set("text/x-vcard","vcf");
				set("video/3gpp","3gp");
				set("video/3gpp2","3g2");
				set("video/h261","h261");
				set("video/h263","h263");
				set("video/h264","h264");
				set("video/jpeg","jpgv");
				set("video/jpm","jpm","jpgm");
				set("video/mj2","mj2","mjp2");
				set("video/mp4","mp4","mp4v","mpg4");
				set("video/mpeg","mpeg","mpg","mpe","m1v","m2v");
				set("video/ogg","ogv");
				set("video/quicktime","qt","mov");
				set("video/vnd.fvt","fvt");
				set("video/vnd.mpegurl","mxu","m4u");
				set("video/vnd.ms-playready.media.pyv","pyv");
				set("video/vnd.vivo","viv");
				set("video/x-fli","fli");
				set("video/x-ms-asf","asf","asx");
				set("video/x-ms-wm","wm");
				set("video/x-ms-wmv","wmv");
				set("video/x-ms-wmx","wmx");
				set("video/x-ms-wvx","wvx");
				set("video/x-msvideo","avi");
				set("video/x-sgi-movie","movie");
				set("x-conference/x-cooltalk","ice");
			}
		}
		
		protected Class<? extends File.Type> defaultTarget() { return File.Type.class; }
		protected java.util.function.Supplier<? extends File.Type> defaultCreator() { return File.Type::new; }
		
		public Template<? extends Endpoint.Type> template()
		{
			return super.template()
				.summary("Storage mapping")
				.description("This endpoint is a direct mapping to a storage location and responds with the target content if present.")
				.add(new Relationship("storage")
					.category(Storage.class)
					.summary("Storage")
					.description("The target storage in which to fetch content.")
					.max(1))
				.add(new Parameter("path")
					.summary("Storage path")
					.description("The path prefix in the storage in case content should be fetched from a subdirectory.")
					.optional(true).defaultValue(Data.of("")))
				.add(new Parameter("filter")
					.summary("URL prefix")
					.description("The URL prefix to filter which requests can be answered by this endpoint. The prefix filter should start with '/'.")
					.optional(true).defaultValue(Data.of("/")))
				;
		}
	}
}
