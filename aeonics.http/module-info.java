module aeonics.http
{
	requires aeonics.boot;
	requires transitive aeonics.core;
	exports aeonics.http;
	
	provides aeonics.Plugin with local.Main;
}
