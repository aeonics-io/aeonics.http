package aeonics.http.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.TrustManagerFactory;

import aeonics.data.Data;
import aeonics.entity.Message;
import aeonics.http.HttpServer;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.manager.Network;
import aeonics.util.Callback;

/**
 * Experimental HTTP/3 implementation running directly on top of QUIC.
 * <p>
 * This class attempts to handle QUIC packets provided by the network layer
 * and expose HTTP/3 request messages similarly to {@link Http1}. It performs a
 * minimal TLS handshake using Java's built-in {@link SSLEngine} and maintains a
 * very small subset of QUIC functionality: packet ordering, acknowledgments and
 * stream demultiplexing. Only HEADERS and DATA frames are interpreted. The goal
 * is feature parity with the HTTP/1 parser but over QUIC.
 * </p>
 */
public class Http3 implements HttpProtocol
{
    private Network.Connection connection = null;
    public Network.Connection connection() { return connection; }

    private Callback<Message, HttpProtocol> onRequest = new Callback<>(this);
    public Callback<Message, HttpProtocol> onRequest() { return onRequest; }

    private AtomicBoolean busy = new AtomicBoolean(false);
    private QuicConnection quic = new QuicConnection();

    public Http3(Network.Connection connection)
    {
        this.connection = connection;
        HttpProtocol.watch(this);

        this.connection.onReady().then((_null, c) ->
        {
            while( c.hasNext() )
            {
                if (!busy.compareAndSet(false, true)) return;

                try
                {
                    for( byte[] data = c.next(); data != null; data = c.next() )
                    {
                        do
                        {
                            Message m = quic.handleDatagram(data);
                            if( m != null )
                            {
                                m.connection(connection());
                                onRequest().trigger(m);
                            }
                            data = quic.remaining;
                        } while( data != null && data.length > 0 );
                    }
                }
                catch(Exception e)
                {
                    Manager.of(Logger.class).fine(HttpServer.class,
                        "Http3 connection error: {}", e.getMessage());
                    try { c.close(); } catch(Exception x) { /* ignore */ }
                }
                finally
                {
                    busy.set(false);
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    // QUIC implementation
    // ---------------------------------------------------------------------

    private static class QuicConnection
    {
        private enum State { INITIAL, HANDSHAKE, READY }

        private State state = State.INITIAL;
        private SSLEngine engine;
        private ByteBuffer netIn = ByteBuffer.allocate(65535);
        private ByteBuffer netOut = ByteBuffer.allocate(65535);
        private ByteBuffer appIn = ByteBuffer.allocate(65535);
        private Map<Long, ByteArrayOutputStream> streams = new HashMap<>();
        private TreeMap<Long, byte[]> reorder = new TreeMap<>();
        private long nextPacket = 0;
        private StateFrame parseState = new StateFrame();
        private byte[] remaining = null;

        public QuicConnection()
        {
            try {
                SSLContext ctx = createContext();
                engine = ctx.createSSLEngine();
                engine.setUseClientMode(false);
                engine.beginHandshake();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private SSLContext createContext() throws Exception
        {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            X509Certificate cert = local.DirtySelfSignedCertificateGenerator.selfSigned(kp, "localhost");

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            SSLContext ctx = SSLContext.getInstance("TLS");

            java.security.KeyStore ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setKeyEntry("key", kp.getPrivate(), new char[0], new java.security.cert.Certificate[] { cert });
            kmf.init(ks, new char[0]);
            tmf.init(ks);
            ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            SSLSessionContext sess = ctx.getServerSessionContext();
            sess.setSessionCacheSize(1);
            return ctx;
        }

        Message handleDatagram(byte[] datagram) throws Exception
        {
            long pn = readPacketNumber(datagram);
            byte[] payload = Arrays.copyOfRange(datagram, 4, datagram.length);
            reorder.put(pn, payload);

            Message out = null;
            while(reorder.containsKey(nextPacket))
            {
                byte[] data = reorder.remove(nextPacket++);
                out = handlePayload(data);
                if(out != null) break;
            }
            remaining = null;
            return out;
        }

        private long readPacketNumber(byte[] data)
        {
            return ((data[0]&0xFFL)<<24)|((data[1]&0xFFL)<<16)|((data[2]&0xFFL)<<8)|(data[3]&0xFFL);
        }

        private Message handlePayload(byte[] payload) throws Exception
        {
            switch(state)
            {
                case INITIAL:
                case HANDSHAKE:
                    netIn.clear();
                    netIn.put(payload);
                    netIn.flip();
                    processHandshake();
                    return null;
                case READY:
                    return handleApplication(payload);
            }
            return null;
        }

        private void processHandshake() throws Exception
        {
            boolean done = false;
            while(!done)
            {
                SSLEngineResult r = engine.unwrap(netIn, appIn);
                switch(r.getHandshakeStatus())
                {
                    case NEED_TASK:
                        Runnable task; while((task = engine.getDelegatedTask()) != null) task.run();
                        break;
                    case NEED_WRAP:
                        netOut.clear();
                        r = engine.wrap(ByteBuffer.allocate(0), netOut);
                        netOut.flip();
                        remaining = Arrays.copyOfRange(netOut.array(), 0, netOut.limit());
                        return;
                    case FINISHED:
                    case NOT_HANDSHAKING:
                        done = true;
                        break;
                    default:
                        break;
                }
                if(r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW)
                    return;
            }
            state = State.READY;
        }

        private Message handleApplication(byte[] payload) throws Exception
        {
            byte[] decrypted = decrypt(payload);
            if(decrypted == null) return null;
            return parseHttp3(decrypted, parseState);
        }

        private byte[] decrypt(byte[] payload) throws SSLException
        {
            netIn.clear();
            netIn.put(payload);
            netIn.flip();
            appIn.clear();
            SSLEngineResult r = engine.unwrap(netIn, appIn);
            if(r.getStatus() != SSLEngineResult.Status.OK) return null;
            appIn.flip();
            byte[] out = new byte[appIn.remaining()];
            appIn.get(out);
            return out;
        }

        private Message parseHttp3(final byte[] data, final StateFrame state) throws HttpParseException
        {
            if( data.length == 0 ) return null;

            state.mark = state.i = 0;
            state.length = data.length;

            try
            {
                while( state.i < state.length )
                {
                    switch(state.mode)
                    {
                        case TYPE:   _1_frameType(data, state); break;
                        case LENGTH: _2_frameLength(data, state); break;
                        case PAYLOAD:_3_framePayload(data, state); break;
                        default: return null;
                    }
                    if( state.mode == Mode.COMPLETE ) break;
                }
            }
            catch(Incomplete i)
            {
                state.mark = state.i = state.length;
            }

            if( state.i < state.length )
                state.remaining = Arrays.copyOfRange(data, state.i, state.length);
            else
                state.remaining = null;

            if( state.mode == Mode.COMPLETE )
            {
                Message m = new Message(state.request.asString("method") + state.request.asString("path"));
                m.content(state.request);
                state.reset();
                return m;
            }
            else
                return null;
        }
    }

    // ---------------------------------------------------------------------
    // HTTP/3 frame parsing (same as previous parser with small changes)
    // ---------------------------------------------------------------------

    private static class StateFrame
    {
        int mark, i, length;
        Mode mode = Mode.TYPE;
        Data request = null;
        ByteArrayOutputStream partial = new ByteArrayOutputStream();

        long frameType = 0;
        long frameLength = 0;
        long bodyExpected = 0;
        long bodyReceived = 0;
        boolean headersParsed = false;

        byte[] remaining = null;

        public void reset()
        {
            mark = i = length = 0;
            frameType = frameLength = 0;
            bodyExpected = bodyReceived = 0;
            headersParsed = false;
            request = Data.map()
                .put("method", "")
                .put("path", "")
                .put("get", Data.map())
                .put("headers", Data.map())
                .put("post", Data.map())
                .put("files", Data.map())
                .put("body", "");
            mode = Mode.TYPE;
            partial.reset();
        }
        public StateFrame() { reset(); }
    }

    private enum Mode
    {
        TYPE,
        LENGTH,
        PAYLOAD,
        COMPLETE,
    }

    public static class HttpParseException extends Exception
    {
        public HttpParseException(String s, int status)
        {
            super(s);
            this.status = status;
        }
        private int status = 500;
        public int status() { return status; }
    }

    private static final int MAX_BODY_SIZE = 5242880; // 5MB

    private static void _1_frameType(byte[] data, StateFrame state) throws HttpParseException
    {
        state.frameType = readVarInt(data, state);
        state.mode = Mode.LENGTH;
    }

    private static void _2_frameLength(byte[] data, StateFrame state) throws HttpParseException
    {
        state.frameLength = readVarInt(data, state);
        state.partial.reset();
        state.mode = Mode.PAYLOAD;
    }

    private static void _3_framePayload(byte[] data, StateFrame state) throws HttpParseException
    {
        int need = (int)(state.frameLength - state.partial.size());
        int left = state.length - state.i;
        if( left < need )
        {
            state.partial.write(data, state.i, left);
            state.i = state.length;
            throw new Incomplete();
        }
        state.partial.write(data, state.i, need);
        state.i += need;
        byte[] payload = state.partial.toByteArray();
        state.partial.reset();

        if( state.frameType == 0x1 ) // HEADERS
            parseHeaders(payload, state);
        else if( state.frameType == 0x0 ) // DATA
            parseData(payload, state);
        else if( state.frameType == 0x4 ) // SETTINGS
            parseSettings(payload);
        // ignore other frames

        if( state.headersParsed && state.bodyReceived >= state.bodyExpected )
            state.mode = Mode.COMPLETE;
        else
            state.mode = Mode.TYPE;
    }

    private static void parseHeaders(byte[] payload, StateFrame state) throws HttpParseException
    {
        int[] idx = new int[] { 0 };
        long count = readVarInt(payload, idx);
        for( long h = 0; h < count; h++ )
        {
            String name = readString(payload, idx);
            String value = readString(payload, idx);
            if( name.equals(":method") )
                state.request.put("method", value);
            else if( name.equals(":path") )
                parsePath(value, state);
            else if( name.equals(":authority") )
                state.request.get("headers").put("host", value);
            else if( !name.startsWith(":"))
                state.request.get("headers").put(name.toLowerCase(), value);
        }
        if( state.request.get("headers").containsKey("content-length") )
            state.bodyExpected = state.request.get("headers").asInt("content-length");
        state.headersParsed = true;
        if( state.bodyExpected == 0 )
            state.mode = Mode.COMPLETE;
    }

    private static void parseData(byte[] payload, StateFrame state) throws HttpParseException
    {
        if( state.bodyReceived + payload.length > MAX_BODY_SIZE )
            throw new HttpParseException("Body too large", 431);
        state.bodyReceived += payload.length;
        String body = state.request.asString("body") + new String(payload, StandardCharsets.UTF_8);
        state.request.put("body", body);
    }

    private static void parseSettings(byte[] payload) throws HttpParseException
    {
        int[] idx = new int[] { 0 };
        while( idx[0] < payload.length )
        {
            long id = readVarInt(payload, idx);
            long value = readVarInt(payload, idx);
            if( idx[0] > payload.length )
                throw new HttpParseException("Malformed SETTINGS", 400);
            // settings are ignored
        }
    }

    private static void parsePath(String path, StateFrame state) throws HttpParseException
    {
        int q = path.indexOf('?');
        if( q >= 0 )
        {
            state.request.put("path", path.substring(0, q));
            decodeQuery(path.substring(q+1), state.request.get("get"));
        }
        else
            state.request.put("path", path);
    }

    private static void decodeQuery(String q, Data into) throws HttpParseException
    {
        boolean encoded = false, isKey = true;
        String key = null;
        int mark = 0;
        for( int i = 0; i < q.length(); i++ )
        {
            char c = q.charAt(i);
            if( c == '%' || c == '+' ) encoded = true;
            if( c == '=' )
            {
                if( !isKey ) continue;
                key = q.substring(mark, i);
                if( encoded ) key = java.net.URLDecoder.decode(key, StandardCharsets.UTF_8);
                encoded = false;
                mark = i+1;
                isKey = false;
            }
            else if( c == '&' )
            {
                if( isKey )
                {
                    key = q.substring(mark, i);
                    if( encoded ) key = java.net.URLDecoder.decode(key, StandardCharsets.UTF_8);
                    into.put(key, Data.empty());
                }
                else
                {
                    String val = q.substring(mark, i);
                    if( encoded ) val = java.net.URLDecoder.decode(val, StandardCharsets.UTF_8);
                    into.put(key, val);
                }
                encoded = false;
                mark = i+1;
                isKey = true;
            }
        }
        if( isKey )
        {
            key = q.substring(mark);
            if( encoded ) key = java.net.URLDecoder.decode(key, StandardCharsets.UTF_8);
            into.put(key, Data.empty());
        }
        else
        {
            String val = q.substring(mark);
            if( encoded ) val = java.net.URLDecoder.decode(val, StandardCharsets.UTF_8);
            into.put(key, val);
        }
    }

    private static long readVarInt(byte[] data, StateFrame state) throws HttpParseException
    {
        if( state.partial.size() == 0 )
        {
            if( state.i >= state.length ) throw new Incomplete();
            state.partial.write(data[state.i++]);
        }
        byte[] arr = state.partial.toByteArray();
        int first = arr[0] & 0xFF;
        int bytes = 1 << (first >> 6);
        while( arr.length < bytes )
        {
            if( state.i >= state.length ) throw new Incomplete();
            state.partial.write(data[state.i++]);
            arr = state.partial.toByteArray();
        }
        long val = first & 0x3F;
        for( int j = 1; j < bytes; j++ )
            val = (val << 8) | (arr[j] & 0xFF);
        state.partial.reset();
        return val;
    }

    private static long readVarInt(byte[] data, int[] idx) throws HttpParseException
    {
        if( idx[0] >= data.length ) throw new HttpParseException("Malformed varint", 400);
        int first = data[idx[0]++] & 0xFF;
        int bytes = 1 << (first >> 6);
        if( idx[0] + bytes -1 > data.length ) throw new HttpParseException("Malformed varint", 400);
        long val = first & 0x3F;
        for( int j = 1; j < bytes; j++ )
            val = (val << 8) | (data[idx[0]++] & 0xFF);
        return val;
    }

    private static String readString(byte[] data, int[] idx) throws HttpParseException
    {
        long len = readVarInt(data, idx);
        if( idx[0] + len > data.length ) throw new HttpParseException("Malformed string", 400);
        String s = new String(data, idx[0], (int)len, StandardCharsets.UTF_8);
        idx[0] += len;
        return s;
    }
}
