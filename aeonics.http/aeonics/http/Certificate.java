package aeonics.http;

import aeonics.data.Data;
import aeonics.util.Tuples.Tuple;

public class Certificate
{
	/**
     * Generate a self-signed certificate and private key for the given host name
     * @param hostname the certificate domain name, if empty, "localhost" is used
     * @return a tuple with the PEM-encoded certificate and the PEM-encoded private key
     * @throws Exception if the certificate and key generation fails
     */
	public static Tuple<Data, Data> generate(String hostname) throws Exception
	{
		if( hostname == null || hostname.isBlank() ) hostname = "localhost";
		
		return local.DirtySelfSignedCertificateGenerator.generate(hostname);
	}
}
