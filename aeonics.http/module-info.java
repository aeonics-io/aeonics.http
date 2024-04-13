module aeonics.http
{
	requires transitive aeonics.system;
	exports aeonics.http;
	
	provides aeonics.Plugin with local.Main;
}
