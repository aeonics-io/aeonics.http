package local;

import java.nio.file.Files;
import java.nio.file.Paths;

import aeonics.Plugin;
import aeonics.data.Data;
import aeonics.entity.Action;
import aeonics.entity.Queue;
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
import aeonics.util.Hardware;
import aeonics.util.Tuples.Tuple;

public class Main extends Plugin
{
	public String summary() { return "Http Server"; }
	public String description() { return "Provides the basic HTTP protocol compatibility and starts the default web server on port 80. The default router action id is set in the configuration with the key \"http.default.router\"."; }
	
	public void start()
	{
		Lifecycle.on(Phase.LOAD, this::onLoad);
		Lifecycle.on(Phase.CONFIG, this::onConfig);
		Lifecycle.on(Phase.RUN, this::onRun);
		Lifecycle.after(Phase.RUN, this::onAfterRun);
	}
	
	private void onLoad()
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
	
	private void onConfig()
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
		c.declare(HttpServer.class, new Parameter("default.tls.enabled")
			.summary("Enable HTTPS")
			.description("If set to true, this parameter enables HTTPS. If the certificate and key are not provided, a new self-signed certificate will be generated.")
			.format(Parameter.Format.BOOLEAN)
			.optional(true)
			.defaultValue(true));
		c.declare(HttpServer.class, new Parameter("default.port")
			.summary("Default HTTP port")
			.description("The port number to use for the default HTTP server.")
			.format(Parameter.Format.NUMBER)
			.optional(true)
			.defaultValue(443));
		c.declare(HttpServer.class, new Parameter("default.address")
			.summary("Default HTTP listening IP address")
			.description("The IP address the default HTTP server will listen to. IPv4 or IPv6 can be specified depending on the system capabilities.")
			.format(Parameter.Format.TEXT)
			.optional(true)
			.defaultValue("0.0.0.0"));
		c.declare(HttpServer.class, new Parameter("initialized")
			.summary("Default HTTP server has been initialized")
			.description("This parameter defines if the default HTTP server has already been initialized (true) or if it should done when starting the run phase (false)."
					+ " This is normally set by the system to detect an initial snapshot.")
			.format(Parameter.Format.BOOLEAN)
			.rule(Parameter.Rule.BOOLEAN)
			.optional(true)
			.defaultValue(false));
	}

	private boolean hasDefaultHttpSetup = false;
	private void onRun()
	{
		new Endpoint.Rest() { }
			.template()
			.summary("Test endpoint")
			.description("This endpoint returns the same response all the time. If this endpoint responds, it means that the system is up and running.")
			.create()
			.<Rest.Type>cast()
			.process(() -> Data.map().put("success", true))
			.url("/api/ping")
			.method("GET");
		
		hasDefaultHttpSetup = Manager.of(Config.class).get(HttpServer.class, "initialized").asBool();
		if( hasDefaultHttpSetup ) return;
		Manager.of(Config.class).set(HttpServer.class, "initialized", true);
		
		Policy.Type policy = new Policy.Allow().template().create(Data.map().put("parameters", Data.map().put("scope", "http")));
		policy.name("Allow http for everyone by default");
		policy.addRelation("rule", new Rule.MatchAll().template().create().name("Everyone"));
		
		Action.Type router = (Action.Type) Factory.of(Action.class).get(Router.class).create(
				Data.map().put("parameters", Data.map().put("allfilters", true).put("allendpoints", true)))
			.name("Default router")
			.addRelation("destinations", new HttpResponse().template().create().name("Http responder"), 
				Data.map().put("input", "response").put("output", "response"));
		
		new CorsFilter().template().create().name("CORS Filter");
		new GzipFilter().template().create().name("GZIP Filter");
		new HeadersFilter().template().create().name("Custom headers filter");
		new OptionsMethodFilter().template().create().name("Options method filter");
		
		Queue.Type queue = new Queue().template().create(Data.map().put("parameters", Data.map().put("concurrency", Hardware.CPU.cores())))
			.addRelation("actions", router, Data.map().put("input", "request"))
			.name("Http request queue");
		Topic.Type topic = new Topic().template().create()
			.addRelation("queues", queue, Data.map().put("binding", "#"))
			.name("http");
		
		// check default https certificate/key
		Config c = Manager.of(Config.class);
		Data crt = c.get(HttpServer.class, "default.tls.certificate");
		Data key = c.get(HttpServer.class, "default.tls.private");
		boolean ssl = c.get(HttpServer.class, "default.tls.enabled").asBool();
		
		if( ssl && !crt.isEmpty() && !key.isEmpty() )
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
		if( ssl && (crt.isEmpty() || key.isEmpty()) )
		{
			Tuple<Data, Data> t = generateSelfSignedCertificate();
			crt = t.a;
			key = t.b;
		}
		
		int port = c.get(HttpServer.class, "default.port").asInt();
		if( port <= 0 ) port = ssl ? 443 : 80;
		Data address = c.get(HttpServer.class, "default.address");
		
		new HttpServer().template().create(Data.map().put("parameters", Data.map()
			.put("address", address)
			.put("port", port)
			.put("certificate", crt)
			.put("key", key)))
			.addRelation("topics", topic, Data.map()
				.put("channel", "request"))
			.name("Http Server");
	}
	
	private void onAfterRun()
	{
		if( hasDefaultHttpSetup ) return;
		
		// The storage endpoint is slow to lookup because it
		// must access the filesystem. When the router looks up the
		// requested path, we should check this one last so that any other
		// endpoint is fast to find.
		// Therefore, we declare the endpoint here so that it is more likely to be last
		// in the registry iterator.
		new Endpoint.File().template().create(Data.map().put("parameters", Data.map().put("filter", "/")))
			.addRelation("storage", new Storage.File()
				.template()
				.create(Data.map().put("parameters", Data.map().put("root", "www"))).name("Web root storage"));
	}
	
	private Tuple<Data, Data> generateSelfSignedCertificate()
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
