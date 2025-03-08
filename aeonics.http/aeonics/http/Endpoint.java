package aeonics.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import aeonics.data.Data;
import aeonics.entity.Entity;
import aeonics.entity.Message;
import aeonics.entity.Registry;
import aeonics.entity.Step;
import aeonics.entity.Step.Destination;
import aeonics.entity.Step.Origin;
import aeonics.entity.Storage;
import aeonics.entity.security.Token;
import aeonics.entity.security.User;
import aeonics.manager.Manager;
import aeonics.manager.Security;
import aeonics.template.Channel;
import aeonics.template.Item;
import aeonics.template.Parameter;
import aeonics.template.Relationship;
import aeonics.util.StringUtils;
import aeonics.util.Tuples.Tuple;
import aeonics.util.Callback;
import aeonics.util.Functions.BiConsumer;
import aeonics.util.Functions.BiFunction;
import aeonics.util.Functions.Function;
import aeonics.util.Functions.Supplier;
import aeonics.util.Functions.TriConsumer;
import aeonics.util.Functions.TriFunction;
import aeonics.util.Json;

/**
 * This item represents an HTTP endpoint that will produce a response to a request.
 */
public abstract class Endpoint extends Item<Endpoint.Type>
{
	/**
	 * Superclass template for endpoints
	 */
	public static class Template extends aeonics.template.Template<Endpoint.Type>
	{
		public Template(Class<? extends Endpoint.Type> target, Class<? extends Endpoint> type)
		{
			super(target, type, Endpoint.class);
		}
		
		/**
		 * Documentation of the return value of the endpoint
		 */
		private String returns = null;
		
		/**
		 * Returns the documentation of the endpoint return value
		 * @return the documentation of the endpoint return value
		 */
		public String returns() { return returns; }
		
		/**
		 * Sets the documentation of the endpoint return value
		 * @param value the documentation of the endpoint return value
		 * @return this
		 */
		public Endpoint.Template returns(String value) { returns = value; return this; }
		
		@Override
		public Data export()
		{
			return super.export()
				.put("returns", returns());
		}
	}
	
	/**
	 * This is the base endpoint.
	 */
	@SuppressWarnings("unchecked")
	public abstract static class Type extends Entity
	{
		/**
		 * Empty constructor
		 */
		protected Type() { super(); }
		
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
		 * @param <T> this endpoint type
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
		 * @param <T> this endpoint type
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
		 * @throws Exception if anything happens, the exception will be wrapped in an {@link HttpException}
		 */
		public abstract Data process(Message request) throws Exception;
		
		@Override
		public Data export() { return super.export().put("method", method()).put("url", url()); }
		@Override
		public String name() { return method() + " " + url(); }
		@Override
		public <T extends Entity> T name(String value) { return (T) this; }
		
		/**
		 * Hardcoded category to the {@link Endpoint} class
		 */
		@Override
		public final String category() { return StringUtils.toLowerCase(Endpoint.class); }
	}
	
	protected Class<? extends Endpoint> category() { return Endpoint.class; }
	
	@Override
	public Endpoint.Template template()
	{
		return new Endpoint.Template(target(), this.getClass()).creator(creator());
	}
	
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
	 *     .create() // &lt;-- create an instance of the entity and register it in the registry
	 *     .&lt;Rest.Type&gt;cast()
	 *     
	 *     // set the rest endpoint logic
	 *     .process(() -&gt; Data.map().put("hello", "world")) // &lt;-- the endpoint logic
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
	 *     .target(Hello.class)
	 *     .creator(Hello::new)
	 *     
	 *     .template() // &lt;-- create the template and register it in the factory
	 *     
	 *     // add all your template documentation
	 *     .summary("Says hello to the world")
	 *     
	 *     .create() // &lt;-- create an instance of the entity and register it in the registry
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
		 * <p>Unless overridden, all instances of this class are marked as internal and not snapshotable in the constructor.</p>
		 */
		@SuppressWarnings("unchecked")
		public static class Type extends Endpoint.Type
		{
			public Type()
			{
				super();
				internal(true);
				snapshotMode(SnapshotMode.NONE);
			}

			@Override
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
			@Override
			public boolean matches(String url)
			{
				if( url == null || url.isEmpty() ) return false;
				if( wildcardUrl == null ) return url.equals(url());
				return StringUtils.simplePathMatches(wildcardUrl, url, urlWordWildcards, urlGlobalWildcards, urlNegators, urlWordDelimiters);
			}
			
			private static final char[] urlWordWildcards = new char[] { '*' };
			private static final char[] urlGlobalWildcards = new char[] { '#' };
			private static final char[] urlNegators = new char[] { '!' };
			private static final char[] urlWordDelimiters = new char[] { '/' };
			
			/**
			 * Handler in case of error
			 */
			private TriFunction<Data, User.Type, Exception, Object> errorHandler = null;
			
			/**
			 * Sets an error handler for this endpoint. This is the opportunity to catch the error and return another response instead.
			 * <p>The handler has the following signature: <code>TriFunction&lt;Data, User.Type, Exception, Data&gt;</code></p>
			 * <ol>
			 * <li>The first argument represents the http request parameters. It may be null if the error is triggered while parsing the parameters.</li>
			 * <li>The second argument is the authenticated user, or {@link User#ANONYMOUS}</li>
			 * <li>The third argument is the error thrown</li>
			 * <li>The handler should return a new response, or null to propagate the error in the response</li>
			 * </ol>
			 * <p>If the handler throws an exception, it has precedence over the endpoint error and will be sent as a response instead.
			 * The output from this handler will be processed by the after handler.</p>
			 * @param <T> this
			 * @param handler the error handler
			 * @return this
			 */
			public <T extends Endpoint.Rest.Type> T error(TriFunction<Data, User.Type, Exception, Object> handler) { errorHandler = handler; return (T) this; }
			
			/**
			 * Sets an error handler for this endpoint.
			 * The consumer cannot return a value to override the response, but may throw another exception if needed.
			 * @see #error(TriFunction)
			 * @param <T> this
			 * @param handler the error handler
			 * @return this
			 */
			public <T extends Endpoint.Rest.Type> T error(TriConsumer<Data, User.Type, Exception> handler) { return error((request, user, error) -> { handler.accept(request, user, error); return null; }); }
			
			/**
			 * Handler called before processing the request
			 */
			private BiFunction<Data, User.Type, Object> beforeHandler = null;
			
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
			 * If the handler throws an exception, it will proceed through the error handler.</p>
			 * @param <T> this
			 * @param handler the before processing handler
			 * @return this
			 */
			public <T extends Endpoint.Rest.Type> T before(BiFunction<Data, User.Type, Object> handler) { beforeHandler = handler; return (T) this; }
			
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
			private TriFunction<Data, User.Type, Data, Object> afterHandler = null;
			
			/**
			 * Sets a handler to intercept the response after it has been generated by this endpoint.
			 * <p>It is the place to perform output filtering before sending the response.
			 * The handler has the following signature: <code>TriFunction&lt;Data, User.Type, Data, Data&gt;</code></p>
			 * <ol>
			 * <li>The first argument represents the http request parameters</li>
			 * <li>The second argument is the authenticated user</li>
			 * <li>The third argument is the response generated by this endpoint</li>
			 * <li>The handler may return a totally new response data object, or null to keep the original</li>
			 * </ol>
			 * <p>It is allowed for the handler to modify the original response data in-place and return null afterwards.
			 * If the handler throws an exception, it will be directly sent as a response instead without proceeding through the error handler.</p>
			 * @param <T> this
			 * @param handler the after processing handler
			 * @return this
			 */
			public <T extends Endpoint.Rest.Type> T after(TriFunction<Data, User.Type, Data, Object> handler) { afterHandler = handler; return (T) this; }
			
			/**
			 * Sets a handler to intercept the response after it has been generated by this endpoint.
			 * The consumer cannot return a value to override the response, but may still modify it in-place or throw an exception if needed.
			 * @see #after(TriFunction)
			 * @param <T> this
			 * @param handler the after processing handler
			 * @return this
			 */
			public <T extends Endpoint.Rest.Type> T after(TriConsumer<Data, User.Type, Data> handler) { return after((request, user, response) -> { handler.accept(request, user, response); return null; }); }
			
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
			 * @throws Exception if anything happens, the exception will be wrapped in an {@link HttpException}
			 */
			public final Data process(Message request) throws Exception
			{
				User.Type user = Registry.of(User.class).get(request.user());
				if( user == null ) user = User.ANONYMOUS;
				
				Data params = null;
				Data response = null;
				try
				{
					params = collectAndValidateParameters(request);
					
					if( beforeHandler != null )
					{
						Data new_params = Data.of(beforeHandler.apply(params, user));
						if( new_params != null && !new_params.isNull() ) params = new_params;
					}
					
					response = Data.of(process(params, user, request));
				}
				catch(Exception t)
				{
					if( errorHandler != null )
					{
						response = Data.of(errorHandler.apply(params, user, t));
						if( response == null || response.isNull() ) throw t;
						else return response;
					}
					else throw t;
				}
				
				if( afterHandler != null )
				{
					Data new_response = Data.of(afterHandler.apply(params, user, response));
					if( new_response != null && !new_response.isNull() ) response = new_response;
				}
				
				return response;
			}
			
			/**
			 * Process function with 3 parameters
			 */
			private TriFunction<Data, User.Type, Message, Object> processor1 = null;
			
			/**
			 * Sets the process function that will replace the {@link #process(Data, User.Type, Message)} method.
			 * @param <T> this
			 * @param processor the process function with 3 input parameters
			 * @return this
			 */
			public <T extends Endpoint.Rest.Type> T process(TriFunction<Data, User.Type, Message, Object> processor) { this.processor1 = processor; return (T) this; }
			
			/**
			 * Processes the request and generates a response.
			 * You may override this method if you only need the validated input parameters but also the original message data.
			 * @param params the validated input parameters
			 * @param user the associated user
			 * @param request the original message data
			 * @return the endpoint response
			 * @throws Exception if anything happens, the exception will be wrapped in an {@link HttpException}
			 * @see #process(Message)
			 */
			public Data process(Data params, User.Type user, Message request) throws Exception
			{
				if( processor1 != null ) return Data.of(processor1.apply(params, user, request));
				else return process(params, user);
			}
			
			/**
			 * Process function with 2 parameters
			 */
			private BiFunction<Data, User.Type, Object> processor2 = null;
			
			/**
			 * Sets the process function that will replace the {@link #process(Data, User.Type)} method.
			 * @param <T> this
			 * @param processor the process function with 2 input parameters
			 * @return this
			 */
			public <T extends Endpoint.Rest.Type> T process(BiFunction<Data, User.Type, Object> processor) { this.processor2 = processor; return (T) this; }
			
			/**
			 * Processes the request and generates a response.
			 * You may override this method if you only need the validated input parameters.
			 * @param params the validated input parameters
			 * @param user the associated user
			 * @return the endpoint response
			 * @throws Exception if anything happens, the exception will be wrapped in an {@link HttpException}
			 * @see #process(Message)
			 */
			public Data process(Data params, User.Type user) throws Exception
			{
				if( processor2 != null ) return Data.of(processor2.apply(params, user));
				else return process(params);
			}
			
			/**
			 * Process function with 1 parameter
			 */
			private Function<Data, Object> processor3 = null;
			
			/**
			 * Sets the process function that will replace the {@link #process(Data)} method.
			 * @param <T> this
			 * @param processor the process function with 1 input parameter
			 * @return this
			 */
			public <T extends Endpoint.Rest.Type> T process(Function<Data, Object> processor) { this.processor3 = processor; return (T) this; }
			
			/**
			 * Processes the request and generates a response.
			 * You may override this method if you only need the validated input parameters.
			 * @param params the validated input parameters
			 * @return the endpoint response
			 * @throws Exception if anything happens, the exception will be wrapped in an {@link HttpException}
			 * @see #process(Message)
			 */
			public Data process(Data params) throws Exception
			{
				if( processor3 != null ) return Data.of(processor3.apply(params));
				else return process();
			}
			
			/**
			 * Process function without parameters
			 */
			private Supplier<Object> processor4 = null;
			
			/**
			 * Sets the process function that will replace the {@link #process()} method.
			 * @param <T> this
			 * @param processor the process function without input parameters
			 * @return this
			 */
			public <T extends Endpoint.Rest.Type> T process(Supplier<Object> processor) { this.processor4 = processor; return (T) this; }
			
			/**
			 * Processes the request and generates a response.
			 * You may override this method if you do not need any input parameters.
			 * @return the endpoint response
			 * @throws Exception if anything happens, the exception will be wrapped in an {@link HttpException}
			 * @see #process(Message)
			 */
			public Data process() throws Exception
			{
				if( processor4 != null ) return Data.of(processor4.get());
				else return Data.of(null);
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
			 * @throws Exception if any parameter validation fails or if the input message is malformed
			 */
			public Data collectAndValidateParameters(Message request) throws Exception
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
					
					if( p.b.format().equals(Parameter.Format.JSON) && value.isString() )
						value = Json.decode(value.asString());
					
					if( !p.b.validate(value) )
						throw new HttpException(400, "Parameter validation failed for '" + p.b.name() + "'");
					params.put(p.b.name(), value);
				}
				return params;
			}
		}
	
		protected Class<? extends Rest.Type> defaultTarget() { return Rest.Type.class; }
		protected java.util.function.Supplier<? extends Rest.Type> defaultCreator() { return Rest.Type::new; }

		@Override
		public Endpoint.Template template()
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
				return firstRelation("storage");
			}

			@Override
			public boolean matches(String url)
			{
				String filter = Objects.requireNonNullElse(url(), "/");
				if( filter != null && !url.startsWith(filter) ) return false;
				
				Storage.Type store = store();
				if( store == null ) return false;
				
				if( url.isBlank() || url.endsWith("/") ) url += "index.html";

				return store.containsEntry(valueOf("path").asString() + "/" + url.substring(filter.length()))
					|| store.containsEntry(valueOf("path").asString() + "/" + url.substring(filter.length()) + ".html")
					|| store.containsEntry(valueOf("path").asString() + "/" + url.substring(filter.length()) + "/index.html");
			}
			
			public Data process(Message request) throws Exception
			{
				String filter = Objects.requireNonNullElse(url(), "/");
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
					.put("mime", Mime.guess(path));
			}
		}
		
		protected Class<? extends File.Type> defaultTarget() { return File.Type.class; }
		protected java.util.function.Supplier<? extends File.Type> defaultCreator() { return File.Type::new; }

		@Override
		public Endpoint.Template template()
		{
			return super.template()
				.summary("Storage mapping")
				.description("This endpoint is a direct mapping to a storage location and responds with the target content if present. "
					+ "If set, the URL of this endpoint acts as a prefix to filter which requests can be answered by this endpoint.")
				.add(new Relationship("storage")
					.category(Storage.class)
					.summary("Storage")
					.description("The target storage in which to fetch content.")
					.max(1))
				.add(new Parameter("path")
					.summary("Storage path")
					.description("The path prefix in the storage in case content should be fetched from a subdirectory.")
					.format(Parameter.Format.TEXT)
					.optional(true).defaultValue(""))
				.<Endpoint.Template>cast()
				.returns("This endpoint returns the corresponding content from the storage with a status code 200.")
				;
		}
	}
	
	// =========================================
	//
	// WEBSOCKET ENDPOINT
	//
	// =========================================
	
	public static class Websocket extends Endpoint
	{
		public static class Type extends Endpoint.Type
		{
			public Type()
			{
				super();
				internal(true);
				snapshotMode(SnapshotMode.NONE);
			}
			
			private Data upgradeResponse(Message request) throws Exception
			{
				Data headers = request.content().get("headers");
				if( !headers.containsKey("upgrade") || !headers.asString("upgrade").equalsIgnoreCase("websocket") || 
					!headers.containsKey("connection") || !headers.asString("connection").toLowerCase().contains("upgrade") )
					return null;
				
				if( !headers.containsKey("sec-websocket-version") || !headers.asString("sec-websocket-version").equals("13") )
					throw new HttpException(426, "Unsupported Websocket Version");
				if( !headers.containsKey("sec-websocket-key") || !StringUtils.isBase64(headers.asString("sec-websocket-key")) )
					throw new HttpException(426, "Invalid Websocket Key");
				
				String key = headers.asString("sec-websocket-key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
				key = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
					.digest(key.getBytes(StandardCharsets.ISO_8859_1)));
				
				return Data.map().put("isHttpResponse", true)
					.put("code", 101)
					.put("headers", Data.map()
						.put("Upgrade", headers.get("upgrade"))
						.put("Sec-Websocket-Accept", key)
						);
			}

			@Override
			public Data process(Message request) throws Exception
			{
				if( User.ANONYMOUS.id().equals(request.user()) )
				{
					// check the Sec-WebSocket-Protocol header for auth
					if( !request.content().get("headers").isEmpty("sec-websocket-protocol") )
					{
						Token token = Manager.of(Security.class).authenticate(request.content().get("headers").asString("sec-websocket-protocol"), true);
						if( token != null && token.isValid() && token.inScope("http") )
						{
							request.metadata().put("token", token);
							
							User.Type current = token.user();
							if( current != null ) request.user(current.id()); 
						}
					}
				}
					
				Data params = collectAndValidateParameters(request);
				Data response = upgradeResponse(request);
				
				final User.Type user = Objects.requireNonNullElse(Registry.of(User.class).get(request.user()), User.ANONYMOUS);
				final aeonics.http.protocol.Websocket ws = new aeonics.http.protocol.Websocket(request.connection());
				
				if( !params.isEmpty("publish") )
				{
					String id = params.asString("publish");
					
					Step.Type next = Registry.of(Step.class).get(id);
					if( next == null ) throw new HttpException(413, "Unknown publishing entity");
					
					Origin.Type o = new Origin() { }
						.template()
						.summary("Websocket")
						.<Origin.Template>cast()
						.output(new Channel("data").summary("Data").description("Messages received from the connected party"))
						.create()
						.internal(true)
						.snapshotMode(SnapshotMode.NONE)
						.cast();
					o.start();
					o.link("data", next, params.asString("input"));
						
					final String key = params.asString("key");
					ws.onRequest().then((message, websocket) ->
					{
						message.user(user.id());
						message.key(valueOf(key, message.content()).asString());
						o.produce(message, "data");
					});
					
					ws.connection().onClose().then(Callback.once((x, y) -> 
					{
						ws.onRequest().clear();
						o.internal(false);
						Registry.of(Step.class).remove(o.id());
					}));
				}
				
				if( !params.isEmpty("subscribe") )
				{
					String id = params.asString("subscribe");
					
					Step.Type previous = Registry.of(Step.class).get(id);
					if( previous == null ) throw new HttpException(413, "Unknown subscription entity");
					
					Destination.Type d = new Destination() { }
						.template()
						.summary("Websocket")
						.<Destination.Template>cast()
						.input(new Channel("data").summary("Data").description("Send data to the connected party"))
						.create()
						.internal(true)
						.snapshotMode(SnapshotMode.NONE)
						.<Destination.Type>cast()
						.processor((message, input) ->
						{
							ws.send(message.content().asString().getBytes(StandardCharsets.ISO_8859_1), aeonics.http.protocol.Websocket.OP_TEXT);
						});
					previous.link(params.asString("output"), d, "data");
					
					ws.connection().onClose().then(Callback.once((x, y) -> 
					{
						d.internal(false);
						Registry.of(Step.class).remove(d.id());
						previous.unlink("data", d, "data");
					}));
				}
				
				return response;
			}
			
			private Data collectAndValidateParameters(Message request) throws Exception
			{
				Data params = Data.map();
				for( Tuple<Data, Parameter> p : parameters().values() )
				{
					Data value = request.content().get("get").get(p.b.name());
					if( value.isNull() )
						value = p.b.defaultValue();
					value = p.b.resolve(value, request.content());
					
					if( !p.b.validate(value) )
						throw new HttpException(400, "Parameter validation failed for '" + p.b.name() + "'");
					params.put(p.b.name(), value);
				}
				return params;
			}
		}
		
		protected Class<? extends Websocket.Type> defaultTarget() { return Websocket.Type.class; }
		protected java.util.function.Supplier<? extends Websocket.Type> defaultCreator() { return Websocket.Type::new; }

		@Override
		public Endpoint.Template template()
		{
			return super.template()
				.summary("Websocket endpoint")
				.description("Generic websocket endpoint that publishes and subscribes to the specified topics. A security check is performed by default "
						+ "to verify if the authenticated user is allowed to publish or subscribe to target topics. The authentication token must be sent "
						+ "in the 'Sec-WebSocket-Protocol' header if not set in the regular 'Authentication' header.")
				.add(new Parameter("subscribe")
					.summary("Subscribe")
					.description("The id of the previous flow step to receive messages from.")
					.format(Parameter.Format.TEXT)
					.optional(true))
				.add(new Parameter("output")
					.summary("Subscriber output channel")
					.description("The name of the output channel of the previous flow step when receiving messages.")
					.format(Parameter.Format.TEXT)
					.optional(true))
				.add(new Parameter("filter")
					.summary("Filter")
					.description("The filter for the subscription.")
					.format(Parameter.Format.TEXT)
					.optional(true)
					.defaultValue("#"))
				.add(new Parameter("publish")
					.summary("Publish")
					.description("The id of the next flow step to forward messages to.")
					.format(Parameter.Format.TEXT)
					.optional(true))
				.add(new Parameter("input")
					.summary("Publisher input channel")
					.description("The name of the input channel of the next flow step when forwarding messages.")
					.format(Parameter.Format.TEXT)
					.optional(true))
				.add(new Parameter("key")
					.summary("Key")
					.description("The publishing key to publish messages.")
					.format(Parameter.Format.TEXT)
					.optional(true)
					.bindable(true)
					.defaultValue("websocket"))
				.<Endpoint.Template>cast()
				.returns("This endpoint opens the websocket communication.")
				.enforceParameterValidation(false)
				.removeParameter("name");
		}
	}
}
