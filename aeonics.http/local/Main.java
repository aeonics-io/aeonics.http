package local;

import aeonics.Plugin;
import aeonics.data.Data;
import aeonics.entity.Action;
import aeonics.entity.Destination;
import aeonics.entity.Origin;
import aeonics.entity.Queue;
import aeonics.entity.Registry;
import aeonics.entity.Storage;
import aeonics.entity.Topic;
import aeonics.http.Endpoint;
import aeonics.http.HttpServer;
import aeonics.http.Router;
import aeonics.http.Filter;
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
			Manager.of(Config.class).set(Router.class, "default", Data.of(Registry.of(Action.class).put(Factory.of(Action.class).get(Router.class).build(null)).id()));
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
				.process(() -> Data.map().put("success", true))
				.url("/api/ping")
				.method("GET")
				)
			.addRelation("endpoints", Registry.of(Endpoint.class).put((Endpoint.Type) Factory.of(Endpoint.class).get(Endpoint.File.class).build(Data.map().put("filter", "/file/"))
				.addRelation("storage", Registry.of(Storage.class).put(Factory.of(Storage.class).get(Storage.File.class).build(Data.map().put("root", "data"))))
				))
			.addRelation("filters", Registry.of(Filter.class).put(Factory.of(Filter.class).get(CorsFilter.class).build(null)))
			.addRelation("filters", Registry.of(Filter.class).put(Factory.of(Filter.class).get(GzipFilter.class).build(null)))
			.addRelation("filters", Registry.of(Filter.class).put(Factory.of(Filter.class).get(HeadersFilter.class).build(null)))
			.addRelation("filters", Registry.of(Filter.class).put(Factory.of(Filter.class).get(OptionsMethodFilter.class).build(null)))
			.addRelation("destinations", Registry.of(Destination.class).put(Factory.of(Destination.class).get(HttpResponse.class).build(null)), 
				Data.map().put("input", "response").put("output", "response"))
		;
		
		String queue = Registry.of(Queue.class).put(Factory.of(Queue.class).get(Queue.class).build(Data.map().put("concurrency", "8")
			.put("actions", Data.list().add(Data.map().put("id", router.id()).put("channel", "request")))
			)).id();
		String topic = Registry.of(Topic.class).put(Factory.of(Topic.class).get(Topic.class).build(Data.map().put("name", "http").put("queues", Data.list()
			.add(Data.map().put("id", queue).put("binding", "#")))
			)).id();
		Registry.of(Origin.class).put(Factory.of(Origin.class).get(HttpServer.class).build(Data.map().put("topics", Data.list()
				.add(Data.map().put("id", topic).put("channel", "request")))
			));
	}
}
