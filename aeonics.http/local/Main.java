package local;

import java.nio.file.Files;
import java.nio.file.Paths;

import aeonics.Plugin;
import aeonics.data.Data;
import aeonics.entity.Action;
import aeonics.entity.Queue;
import aeonics.entity.Registry;
import aeonics.entity.Storage;
import aeonics.entity.Topic;
import aeonics.entity.security.Policy;
import aeonics.entity.security.Rule;
import aeonics.http.Endpoint;
import aeonics.http.Endpoint.Rest;
import aeonics.http.HttpServer;
import aeonics.http.Router;
import aeonics.http.HttpResponse;
import aeonics.http.filter.CorsFilter;
import aeonics.http.filter.GzipFilter;
import aeonics.http.filter.HeadersFilter;
import aeonics.http.filter.HttpsFilter;
import aeonics.http.filter.OptionsMethodFilter;
import aeonics.manager.Config;
import aeonics.manager.Lifecycle;
import aeonics.manager.Manager;
import aeonics.template.Factory;
import aeonics.template.Parameter;
import aeonics.manager.Lifecycle.Phase;
import aeonics.manager.Logger;
import aeonics.util.Callback;
import aeonics.util.Hardware;
import aeonics.util.Tuple;

public class Main extends Plugin
{
	public String summary() { return "Http Server"; }
	public String description() { return "Provides the basic HTTP protocol compatibility and starts the default web server on port 80. The default router action id is set in the configuration with the key \"http.default.router\"."; }
	
	public void start()
	{
		Manager.of(Lifecycle.class).on(Phase.LOAD, Callback.once(() -> onLoad()));
		Manager.of(Lifecycle.class).on(Phase.CONFIG, Callback.once(() -> onConfig()));
		Manager.of(Lifecycle.class).on(Phase.RUN, Callback.once(() -> onRun()));
		Manager.of(Lifecycle.class).before(Phase.RUN, Callback.once(() -> onBeforeRun()));
	}
	
	private static void onLoad()
	{
		Factory.add(new Router());
		Factory.add(new Endpoint.File());
		Factory.add(new CorsFilter());
		Factory.add(new GzipFilter());
		Factory.add(new HeadersFilter());
		Factory.add(new HttpsFilter());
		Factory.add(new OptionsMethodFilter());
		Factory.add(new HttpServer());
		Factory.add(new HttpResponse());
	}
	
	private static void onConfig()
	{
		Config c = Manager.of(Config.class);
		
		c.declare(HttpServer.class, new Parameter("default.tls.certificate")
			.summary("Default HTTPS certificate")
			.description("The certificate to use by the default HTTP Server entity. The certificate should be provided in PEM-encoded base64 format. It may be the path to a local file.")
			.format(Parameter.Format.TEXT)
			.optional(true)
			.defaultValue(Data.empty()));
		c.declare(HttpServer.class, new Parameter("default.tls.private")
			.summary("Default HTTPS private key")
			.description("The private key to use by the default HTTP Server entity. The private key should be provided in PEM-encoded base64 format. It may be the path to a local file.")
			.format(Parameter.Format.TEXT)
			.optional(true)
			.defaultValue(Data.empty()));
	}
	
	private static void onBeforeRun()
	{
		if( !Manager.of(Config.class).contains(Router.class, "default") )
			Manager.of(Config.class).set(Router.class, "default", Data.of(
				Factory.of(Action.class).get(Router.class).build(
					Data.map().put("allfilters", true).put("allendpoints", true)
					).name("Default router").id()));
	}
	
	private static void onRun()
	{
		Policy.Type policy = new Policy.Allow().template().build(Data.map().put("scope", "http"));
		policy.name("Allow http for everyone by default");
		policy.addRelation("rule", new Rule.MatchAll().template().build().name("Everyone"));
		
		Action.Type router = (Action.Type) Registry.of(Action.class).get(Manager.of(Config.class).get(Router.class, "default").asString())
			.addRelation("destinations", new HttpResponse().template().build().name("Http responder"), 
				Data.map().put("input", "response").put("output", "response"));
		
		new Endpoint.Rest() { }
			.template()
			.summary("Test endpoint")
			.description("This endpoint returns the same response all the time. If this endpoint responds, it means that the system is up and running.")
			.build()
			.<Rest.Type>cast()
			.process(() -> Data.map().put("success", true))
			.url("/api/ping")
			.method("GET");
		new Endpoint.File().template().build(Data.map().put("filter", "/"))
			.addRelation("storage", new Storage.File().template().build(Data.map().put("root", "www")).name("Web root storage")
			);
		new CorsFilter().template().build().name("CORS Filter");
		new GzipFilter().template().build().name("GZIP Filter");
		new HeadersFilter().template().build().name("Custom headers filter");
		new OptionsMethodFilter().template().build().name("Options method filter");
		
		String queue = new Queue().template().build(Data.map().put("concurrency", Hardware.CPU.cores())
			.put("actions", Data.list().add(Data.map().put("id", router.id()).put("input", "request")))
			).name("Http request queue").id();
		String topic = new Topic().template().build(Data.map().put("queues", Data.list()
			.add(Data.map().put("id", queue).put("binding", "#")))
			).name("http").id();
		
		// check default https certificate/key
		Config c = Manager.of(Config.class);
		Data crt = c.get(HttpServer.class, "default.tls.certificate");
		Data key = c.get(HttpServer.class, "default.tls.private");
		if( !crt.isEmpty() && !key.isEmpty() )
		{
			try
			{
				if( Files.isRegularFile(Paths.get(crt.asString())) )
					crt = Data.of(new String(Files.readAllBytes(Paths.get(crt.asString()))));
				if( Files.isRegularFile(Paths.get(key.asString())) )
					key = Data.of(new String(Files.readAllBytes(Paths.get(key.asString()))));
			}
			catch(Exception e)
			{
				crt = key = Data.empty();
			}
		}
		if( crt.isEmpty() || key.isEmpty() )
		{
			Tuple<Data, Data> t = generateSelfSignedCertificate();
			crt = t.a;
			key = t.b;
		}
		
		new HttpServer().template().build(Data.map()
			.put("port", 443)
			.put("certificate", crt)
			.put("key", key)
			.put("topics", Data.list()
				.add(Data.map()
					.put("id", topic)
					.put("channel", "request"))))
			.name("Http Server");
	}
	
	private static Tuple<Data, Data> generateSelfSignedCertificate()
	{
		long start = System.currentTimeMillis();
		Manager.of(Logger.class).info(HttpServer.class, "Generating self-signed HTTPS certificate...");
		try
		{
			Tuple<Data, Data> t = DirtySelfSignedCertificateGenerator.generate("localhost");
			Manager.of(Logger.class).info(HttpServer.class, "Certificate generation completed in {}ms", System.currentTimeMillis()-start);
			return t;
		}
		catch(Exception e)
		{
			Manager.of(Logger.class).severe(HttpServer.class, e);
			throw new RuntimeException("Certificate generation failed");
		}
	}
}
