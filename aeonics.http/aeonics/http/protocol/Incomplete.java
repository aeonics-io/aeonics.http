package aeonics.http.protocol;

/**
 * This exception is meant to be thrown from a stream parser to signal that it cannot
 * proceed until there are more bytes to be parsed.
 */
public class Incomplete extends RuntimeException
{
	/**
	 * Signal that an unknown number of bytes are needed
	 */
	public Incomplete() { }
	
	/**
	 * The number of additional bytes that are expected.
	 * 0 if unknown.
	 */
	public int expected = 0;
	
	/**
	 * Creates a demand for more bytes
	 * @param expected the number of additional bytes
	 */
	public Incomplete(int expected) { this.expected = expected; }
}
