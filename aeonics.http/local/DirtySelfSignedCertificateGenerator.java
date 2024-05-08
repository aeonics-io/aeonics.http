package local;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import aeonics.data.Data;
import aeonics.util.Tuple;

/**
 * This class generates a very basic self signed certificate.
 * It is most probably inefficient and has absolutely no checks whatsoever.
 * Nevertheless, it seems to do the job.
 */
public class DirtySelfSignedCertificateGenerator 
{
	private static final byte INTEGER_TAG = 0x02;
    private static final byte BIT_STRING_TAG = 0x03;
    private static final byte NULL_TAG = 0x05;
    private static final byte OBJECT_IDENTIFIER_TAG = 0x06;
    private static final byte PRINTABLE_STRING_TAG = 0x13;
    private static final byte GENERALIZED_TIME_TAG = 0x18;
    private static final byte SEQUENCE_TAG = 0x30;
    private static final byte SET_TAG = 0x31;
    
    private static byte[] encodeSequence(byte[]... in) throws IOException
    {
    	ByteArrayOutputStream out = new ByteArrayOutputStream();
    	int length = 0;
    	for( byte[] i : in ) length += i.length;
    	out.write(SEQUENCE_TAG);
    	out.write(getLengthBytes(length));
    	for( byte[] i : in ) out.write(i);
    	return out.toByteArray();
    }
    
    private static byte[] encode(byte tag, byte[] data) throws IOException
    {
    	ByteArrayOutputStream out = new ByteArrayOutputStream();
    	int length = data.length;
    	out.write(tag);
    	out.write(getLengthBytes(length));
    	out.write(data);
    	return out.toByteArray();
    }
    
    private static byte[] encodeTime(Date date) throws IOException
    {
    	TimeZone tz = TimeZone.getTimeZone("GMT");
    	SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US);
        sdf.setTimeZone(tz);
        byte[] time = (sdf.format(date)).getBytes("ISO-8859-1");
        
        return encode(GENERALIZED_TIME_TAG, time);
    }
    
    private static byte[] getLengthBytes(int length)
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (length < 128) {
        	baos.write((byte)length);

        } else if (length < (1 << 8)) {
        	baos.write((byte)0x081);
        	baos.write((byte)length);

        } else if (length < (1 << 16)) {
        	baos.write((byte)0x082);
        	baos.write((byte)(length >> 8));
        	baos.write((byte)length);

        } else if (length < (1 << 24)) {
        	baos.write((byte)0x083);
        	baos.write((byte)(length >> 16));
        	baos.write((byte)(length >> 8));
        	baos.write((byte)length);

        } else {
        	baos.write((byte)0x084);
        	baos.write((byte)(length >> 24));
        	baos.write((byte)(length >> 16));
        	baos.write((byte)(length >> 8));
        	baos.write((byte)length);
        }
        return baos.toByteArray();
    }
    
    private static byte[] generateCertificate(PublicKey publicKey, String hostname) throws IOException 
    {
    	byte[] serialnumber = BigInteger.valueOf(System.nanoTime()).toByteArray();
		
		return encodeSequence(
			// 1) write the version
			encode((byte)0xA0, encode(INTEGER_TAG, new byte[] { 2 })),
			
			// 2) write serial number
			encode(INTEGER_TAG, serialnumber),
			
			// 3) write algorithm
			encodeSequence(
        		// OID for SHA256withRSA
        		encode(OBJECT_IDENTIFIER_TAG, new byte[] { 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B }),
        		encode(NULL_TAG, new byte[0])
        		),
			
			// 4) write issuer
			encodeSequence(
				encode(SET_TAG, encodeSequence(
					encode(OBJECT_IDENTIFIER_TAG, new byte[] { 0x55, 0x04, 0x03 }),
					encode(PRINTABLE_STRING_TAG, hostname.getBytes())
					))
				),
			
			// 5) write validity interval
			encodeSequence(
				encodeTime(new Date(System.currentTimeMillis() - 60 * 60 * 1000)),
				encodeTime(new Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000))
				),
			
			// 6) write subject
			encodeSequence(
				encode(SET_TAG, encodeSequence(
					encode(OBJECT_IDENTIFIER_TAG, new byte[] { 0x55, 0x04, 0x03 }),
					encode(PRINTABLE_STRING_TAG, hostname.getBytes())
					))
				),
			
			// 7) write public key
			publicKey.getEncoded()
		);
	}
    
    private static byte[] signCertificate(PrivateKey privateKey, byte[] certificate) throws Exception
    {
    	Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(certificate);
        
        ByteArrayOutputStream sig = new ByteArrayOutputStream();
        sig.write(0); // needed for the bit string
        sig.write(signature.sign());
        
        return encodeSequence(
        	// 1) write the certificate data
        	certificate, 
        	
        	// 2) write the algorithm
        	encodeSequence(
        		// OID for SHA256withRSA
        		encode(OBJECT_IDENTIFIER_TAG, new byte[] { 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B }),
        		encode(NULL_TAG, new byte[0])
        		),
        	
        	// 3) write the signature
        	encode(BIT_STRING_TAG, sig.toByteArray())
        	);
    }
    
    /**
     * Generate a certificate for the given host name
     * @param hostname the certificate domain name
     * @return a tuple with the PEM-encoded certificate and the PEM-encoded private key
     * @throws Exception if anything bad happens
     */
    public static Tuple<Data, Data> generate(String hostname) throws Exception
    {
    	KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(4096);
        KeyPair keyPair = keyGen.generateKeyPair();
        PublicKey publicKey = keyPair.getPublic();
        PrivateKey privateKey = keyPair.getPrivate();
        
        ByteArrayOutputStream crt = new ByteArrayOutputStream();
        crt.write("-----BEGIN CERTIFICATE-----\n".getBytes());
        crt.write(Base64.getEncoder().encode(
        	signCertificate(privateKey,
        		generateCertificate(publicKey, hostname))));
        crt.write("\n-----END CERTIFICATE-----".getBytes());
        
        ByteArrayOutputStream key = new ByteArrayOutputStream();
        key.write("-----BEGIN RSA PRIVATE KEY-----\n".getBytes());
        key.write(Base64.getEncoder().encode(privateKey.getEncoded()));
        key.write("\n-----END RSA PRIVATE KEY-----".getBytes());
        
        return Tuple.of(
			Data.of(new String(crt.toByteArray())),
			Data.of(new String(key.toByteArray()))
			);
    }
}
