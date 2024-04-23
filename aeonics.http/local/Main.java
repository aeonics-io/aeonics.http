package local;

import aeonics.Plugin;
import aeonics.data.Data;
import aeonics.entity.Action;
import aeonics.entity.Queue;
import aeonics.entity.Registry;
import aeonics.entity.Storage;
import aeonics.entity.Topic;
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
import aeonics.manager.Lifecycle.Phase;
import aeonics.util.Callback;
import aeonics.util.Hardware;

public class Main extends Plugin
{
	public String summary() { return "Http Server"; }
	public String description() { return "Provides the basic HTTP protocol compatibility and starts the default web server on port 80. The default router action id is set in the configuration with the key \"http.default.router\"."; }
	
	public void start()
	{
		Manager.of(Lifecycle.class).on(Phase.LOAD, Callback.once(() -> onLoad()));
		
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
	
	private static void onBeforeRun()
	{
		if( !Manager.of(Config.class).contains(Router.class, "default") )
			Manager.of(Config.class).set(Router.class, "default", Data.of(new Router().template().build().name("Default router").id()));
	}
	
	private static void onRun()
	{
		Action.Type router = Registry.of(Action.class).get(Manager.of(Config.class).get(Router.class, "default").asString());
		router
			.addRelation("endpoints", new Endpoint.Rest() { }
				.template()
				.summary("Test endpoint")
				.description("This endpoint returns the same response all the time. If this endpoint responds, it means that the system is up and running.")
				.build()
				.<Rest.Type>cast()
				.process(() -> Data.map().put("success", true))
				.url("/api/ping")
				.method("GET")
				)
			.addRelation("endpoints", new Endpoint.File().template().build(Data.map().put("filter", "/file/"))
				.addRelation("storage", new Storage.File().template().build(Data.map().put("root", "data")).name("Web root storage")
				))
			.addRelation("filters", new CorsFilter().template().build().name("CORS Filter"))
			.addRelation("filters", new GzipFilter().template().build().name("GZIP Filter"))
			.addRelation("filters", new HeadersFilter().template().build().name("Custom headers filter"))
			.addRelation("filters", new OptionsMethodFilter().template().build().name("Options method filter"))
			.addRelation("destinations", new HttpResponse().template().build().name("Http responder"), 
				Data.map().put("input", "response").put("output", "response"))
		;
		
		String queue = new Queue().template().build(Data.map().put("concurrency", Hardware.CPU.cores())
			.put("actions", Data.list().add(Data.map().put("id", router.id()).put("input", "request")))
			).name("Http request queue").id();
		String topic = new Topic().template().build(Data.map().put("queues", Data.list()
			.add(Data.map().put("id", queue).put("binding", "#")))
			).name("http").id();
		
		new HttpServer().template().build(Data.map()
			.put("topics", Data.list()
				.add(Data.map()
					.put("id", topic)
					.put("channel", "request"))))
			.name("Http Server");
	}
}
