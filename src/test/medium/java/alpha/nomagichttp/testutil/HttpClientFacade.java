package alpha.nomagichttp.testutil;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.util.Headers;
import io.netty.buffer.ByteBuf;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.http.ProtocolVersion;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.client.util.StringRequestContent;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.ByteBufMono;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClient.ResponseReceiver;
import reactor.netty.http.client.HttpClientResponse;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.net.http.HttpClient.Version;
import static java.net.http.HttpRequest.BodyPublisher;
import static java.net.http.HttpRequest.BodyPublishers;
import static java.net.http.HttpResponse.BodyHandlers;
import static java.util.Arrays.stream;
import static java.util.Locale.ROOT;
import static java.util.Objects.deepEquals;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.function.Predicate.not;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A HTTP client API that delegates to another {@link Implementation
 * implementation}.<p>
 * 
 * The NoMagicHTTP's own {@link TestClient} is <i>not</i> supported as a
 * delegate. The {@code TestClient} should be used explicitly in its own
 * separated test as it offers a comprehensive low-level API (with background
 * assertions such as expect-no-trailing-bytes from the server) at the same time
 * it also makes the HTTP exchange more readable (most usages simply write and
 * read strings). Where suitable, a subsequent "compatibility" test using the
 * client facade can then be declared whose purpose it is to ensure, well,
 * compatibility. E.g.
 * <pre>
 *   {@literal @}Test
 *   void helloWorld() {
 *       // Very clear for human, and also very detailed, explicit (we like)
 *       TestClient client = ...
 *       String response = client.writeReadTextUntilNewlines("GET / HTTP/1.1 ...");
 *       assertThat(response).isEqualTo("HTTP/1.1 200 OK ...");
 *   }
 *   
 *   {@literal @}ParameterizedTest
 *   {@literal @}EnumSource
 *   void helloWorld_compatibility(HttpClientFacade.Implementation impl) {
 *       // Explosion of types and methods. Only God knows what happens. But okay, good having.
 *       int serverPort = ...
 *       HttpClientFacade client = impl.create(serverPort);
 *       ResponseFacade{@literal <}String{@literal >} response = client.getText("/", HTTP_1_1);
 *       assertThat(response.statusCode())...
 *   }
 * </pre>
 * 
 * The underlying clients used does not expose an API for user-control of the
 * connection, and so, the facade implementation can do no better. In fact, the
 * life-cycle and performance characteristics of the facade and its underlying
 * client objects are pretty much unknown. Most of them have - unfortunately
 * quite expectedly - zero documentation regarding the client's life-cycle and
 * how it should be cached and used. Never mind concerns such as thread-safety
 * and identity lol. Hence, this class will mostly not cache the client object,
 * using one new client for each request executed.<p>
 * 
 * Likely, the underlying client connection will live in a client-specific
 * connection pool until timeout. Attempts to hack the connection may fail. For
 * example, the JDK client will throw an {@code IllegalArgumentException} if
 * the "Connection: close" header is set.<p>
 * 
 * But, if the connection is never closed, then a test class extending {@code
 * AbstractRealTest} will timeout after each test when the superclass stops the
 * server and gracefully awaits child channel closures. To fix this problem, the
 * test ought to close the child from the server-installed request handler.<p>
 * 
 * A specified HTTP version may be rejected with an {@code
 * IllegalArgumentException}, but only on a best-effort basis. Which versions
 * specifically a client implementation supports is not always that clear
 * hahaha. The argument will be passed forward to the client who may then blow
 * up with another exception.<p>
 * 
 * This class is not thread-safe and does not implement {@code hashCode} or
 * {@code equals}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public abstract class HttpClientFacade
{
    /**
     * Supported delegate implementations.
     */
    public enum Implementation {
        /**
         * Supports version HTTP/1.1 and HTTP/2.<p>
         * 
         * Known perks:
         * <ol>
         *   <li>Has no API for retrieval of the reason-phrase.</li>
         *   <li>Does not support setting a "Connection" header.</li>
         *   <li>Does not support HTTP method CONNECT.</li>
         * </ol>
         */
        JDK (JDK::new),
        
        /**
         * What HTTP version this client supports is slightly unknown. JavaDoc
         * for OkHttp 4 (the client version currently used) is - perhaps not
         * surprisingly,
         * <a href="https://square.github.io/okhttp/4.x/okhttp/okhttp3/-ok-http-client/protocols/">empty</a>.
         * <a href="https://square.github.io/okhttp/3.x/okhttp/okhttp3/OkHttpClient.Builder.html#protocols-java.util.List-">OkHttp 3</a>
         * indicates HTTP/1.0 (and consequently 0.9?) is not supported. I guess
         * we'll find out.
         */
        OKHTTP (OkHttp::new),
        
        /**
         * Apache HttpClient.
         * 
         * @see <a href="https://hc.apache.org/httpcomponents-client-5.1.x/index.html">website</a>
         */
        APACHE (Apache::new),
        
        /**
         * Jetty HttpClient.
         * 
         * @see <a href="https://www.eclipse.org/jetty/documentation/jetty-11/programming-guide/index.html#pg-client-http">website</a>
         */
        JETTY (Jetty::new),
        
        /**
         * Reactor-Netty HttpClient.
         * 
         * @see <a href="https://projectreactor.io/docs/netty/release/reference/index.html#http-client">website</a>
         */
        REACTOR (Reactor::new);
        
        // TODO: Helidon has a client built on top of Netty. Add?
        
        /**
         * Construct all implementations.
         * 
         * @param port of server
         * @return a stream of client facades
         */
        public static Stream<HttpClientFacade> createAll(int port) {
            return createAllExceptFor(port);
        }
        
        /**
         * Construct almost all implementations, excluding some.
         * 
         * @param port of server
         * @param impl implementations to exclude
         * @return a stream of client facades
         */
        public static Stream<HttpClientFacade> createAllExceptFor(int port, Implementation... impl) {
            var stream = Arrays.stream(values());
            if (impl.length > 0) {
                var set = EnumSet.of(impl[0], impl);
                stream = stream.filter(not(set::contains));
            }
            return stream.map(i -> i.create(port));
        }
        
        private final BiFunction<Implementation, Integer, HttpClientFacade> factory;
        
        Implementation(BiFunction<Implementation, Integer, HttpClientFacade> f) {
            factory = f;
        }
        
        /**
         * Create the client facade.
         * 
         * @param port of server
         * @return a client facade
         */
        public final HttpClientFacade create(int port) {
            return factory.apply(this, port);
        }
    }
    
    private final Implementation impl;
    private final int port;
    // "permits null elements", whatever that means
    private Map<String, List<String>> headers;
    
    /**
     * Constructs a {@code HttpClientFacade}.
     * 
     * @param impl enum instance
     * @param port of server
     */
    protected HttpClientFacade(Implementation impl, int port) {
        this.impl = impl;
        this.port = port;
        this.headers = Map.of();
    }
    
    /**
     * Returns the enum instance representing this implementation.
     * 
     * @return the enum instance representing this implementation
     */
    public final Implementation toEnum() {
        return impl;
    }
    
    @Override
    public final String toString() {
        return getClass().getSimpleName();
    }
    
    /**
     * Add a header that will be added to each request this client sends.
     * 
     * @param name of header
     * @param value of header
     * @return this for chaining/fluency
     */
    public final HttpClientFacade addClientHeader(String name, String value) {
        requireNonNull(name);
        requireNonNull(value);
        if (headers.isEmpty()) { // i.e. == Map.of()
            headers = new LinkedHashMap<>();
        }
        headers.computeIfAbsent(name, k -> new ArrayList<>(1)).add(value);
        return this;
    }
    
    /**
     * Create a URI with "http://localhost:{port}" prefixed to the specified
     * {@code path}.
     * 
     * @param path of server resource
     * @return a URI
     */
    protected final URI withBase(String path) {
        return URI.create("http://localhost:" + port + path);
    }
    
    /**
     * Copy all headers contained in this class into the given [mutable] sink.
     * 
     * @param sink target
     */
    protected void copyClientHeaders(BiConsumer<String, String> sink) {
        headers.forEach((name, values) ->
                values.forEach(v -> sink.accept(name, v)));
    }
    
    /**
     * Iterate all headers contained in this client.
     * 
     * @return an iterator
     */
    protected Iterable<Map.Entry<String, List<String>>> clientHeaders() {
        return headers.entrySet();
    }
    
    /**
     * Execute a GET request expecting no response body.
     * 
     * @param path of server resource
     * @param version of HTTP
     * @return the response
     *
     * @throws IllegalArgumentException
     *             if no equivalent implementation-specific version exists, or
     *             if the version is otherwise not supported (too old or too new) 
     * @throws IOException
     *             if an I/O error occurs when sending or receiving
     * @throws InterruptedException
     *             if the operation is interrupted
     * @throws ExecutionException
     *             if an underlying asynchronous operation fails
     * @throws TimeoutException
     *             if an otherwise asynchronous operation times out
     *             (timeout duration not specified)
     */
    // Is not final only because Reactor-Netty needs a custom hacked solution
    public ResponseFacade<Void> getEmpty(String path, HttpConstants.Version version)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        
        ResponseFacade<byte[]> rsp = getBytes(path, version);
        var b = rsp.body();
        if (b != null) {
            assertThat(b).isEmpty();
        }
        return retype(rsp);
    }
    
    private static <T, U> T retype(U u) {
        @SuppressWarnings("unchecked")
        T t = (T) u;
        return t;
    }
    
    /**
     * Execute a GET request expecting bytes in the response body.
     * 
     * @param path of server resource
     * @param version of HTTP
     * @return the response
     * 
     * @throws IllegalArgumentException
     *             if no equivalent implementation-specific version exists, or
     *             if the version is otherwise not supported (too old or too new) 
     * @throws IOException
     *             if an I/O error occurs when sending or receiving
     * @throws InterruptedException
     *             if the operation is interrupted
     * @throws ExecutionException
     *             if an underlying asynchronous operation fails
     * @throws TimeoutException
     *             if an otherwise asynchronous operation times out
     *             (timeout duration not specified)
     */
    public abstract ResponseFacade<byte[]> getBytes(String path, HttpConstants.Version version)
            throws IOException, InterruptedException, ExecutionException, TimeoutException;
    
    /**
     * Execute a GET request expecting text in the response body.<p>
     * 
     * Which charset to use for decoding is for the client implementation to
     * decide. In practice, this will likely be extracted from the Content-Type
     * header.
     * 
     * @param path of server resource
     * @param version of HTTP
     * @return the response
     * 
     * @throws IllegalArgumentException
     *             if no equivalent implementation-specific version exists, or
     *             if the version is otherwise not supported (too old or too new) 
     * @throws IOException
     *             if an I/O error occurs when sending or receiving
     * @throws InterruptedException
     *             if the operation is interrupted
     * @throws ExecutionException
     *             if an underlying asynchronous operation fails
     * @throws TimeoutException
     *             if an otherwise asynchronous operation times out
     *             (timeout duration not specified)
     */
    public abstract ResponseFacade<String> getText(String path, HttpConstants.Version version)
            throws IOException, InterruptedException, ExecutionException, TimeoutException;
    
    /**
     * Execute a POST request expecting text in the response body.<p>
     * 
     * Which charset to use for decoding is for the client implementation to
     * decide.
     * 
     * @implSpec
     * The implementation must <i>assert</i> that no body was received without
     * discarding.
     * 
     * @param path of server resource
     * @param version of HTTP
     * @param body of request
     * @return the response
     * 
     * @throws IllegalArgumentException
     *             if no equivalent implementation-specific version exists, or
     *             if the version is otherwise not supported (too old or too new)
     * @throws IOException
     *             if an I/O error occurs when sending or receiving
     * @throws InterruptedException
     *             if the operation is interrupted
     * @throws ExecutionException
     *             if an underlying asynchronous operation fails
     * @throws TimeoutException
     *             if an otherwise asynchronous operation times out
     *             (timeout duration not specified)
     */
    public abstract ResponseFacade<String> postAndReceiveText(
            String path, HttpConstants.Version version, String body)
            throws IOException, InterruptedException, ExecutionException, TimeoutException;
    
    /**
     * Execute a POST request expecting no response body.
     * 
     * @param path of server resource
     * @param version of HTTP
     * @param body of request
     * @return the response
     * 
     * @throws IllegalArgumentException
     *             if no equivalent implementation-specific version exists, or
     *             if the version is otherwise not supported (too old or too new)
     * @throws IOException
     *             if an I/O error occurs when sending or receiving
     * @throws InterruptedException
     *             if the operation is interrupted
     * @throws ExecutionException
     *             if an underlying asynchronous operation fails
     * @throws TimeoutException
     *             if an otherwise asynchronous operation times out
     *             (timeout duration not specified)
     */
    public abstract ResponseFacade<Void> postBytesAndReceiveEmpty(
            String path, HttpConstants.Version version, byte[] body)
            throws IOException, InterruptedException, ExecutionException, TimeoutException;
    
    private static class JDK extends HttpClientFacade {
        private final java.net.http.HttpClient c;
        
        JDK(Implementation impl, int port) {
            super(impl, port);
            c = java.net.http.HttpClient.newHttpClient();
        }
        
        @Override
        public ResponseFacade<byte[]> getBytes(String path, HttpConstants.Version ver)
                throws IOException, InterruptedException
        {
            return execute(GET(path, ver), BodyHandlers.ofByteArray());
        }
        
        @Override
        public ResponseFacade<String> getText(String path, HttpConstants.Version ver)
                throws IOException, InterruptedException
        {
            return execute(GET(path, ver), BodyHandlers.ofString());
        }
        
        @Override
        public ResponseFacade<String> postAndReceiveText(
                String path, HttpConstants.Version ver, String body)
                throws IOException, InterruptedException
        {
            var req = POST(path, ver, BodyPublishers.ofString(body));
            return execute(req, BodyHandlers.ofString());
        }
        
        @Override
        public ResponseFacade<Void> postBytesAndReceiveEmpty(
                String path, HttpConstants.Version ver, byte[] body)
                throws IOException, InterruptedException
        {
            var req = POST(path, ver, BodyPublishers.ofByteArray(body));
            return execute(req, BodyHandlers.ofByteArray())
                    .assertEmpty();
        }
        
        private HttpRequest GET(String path, HttpConstants.Version ver) {
            return newRequest("GET", path, ver, BodyPublishers.noBody());
        }
        
        private HttpRequest POST(String path, HttpConstants.Version ver, BodyPublisher body) {
            return newRequest("POST", path, ver, body);
        }
        
        private HttpRequest newRequest(
                String method, String path, HttpConstants.Version ver, BodyPublisher reqBody)
        {
            var b = HttpRequest.newBuilder()
                    .method(method, reqBody)
                    .uri(withBase(path))
                    .version(toJDKVersion(ver));
            copyClientHeaders(b::header);
            return b.build();
        }
        
        private <B> ResponseFacade<B> execute(
                HttpRequest req,
                HttpResponse.BodyHandler<B> rspBodyConverter)
                throws IOException, InterruptedException
        {
            var rsp = c.send(req, rspBodyConverter);
            return ResponseFacade.fromJDK(rsp);
        }
        
        private static Version toJDKVersion(HttpConstants.Version ver) {
            final Version jdk;
            switch (ver) {
                case HTTP_0_9:
                case HTTP_1_0:
                case HTTP_3:
                    throw new IllegalArgumentException("Not supported.");
                case HTTP_1_1:
                    jdk = Version.HTTP_1_1;
                    break;
                case HTTP_2:
                    jdk = Version.HTTP_2;
                    break;
                default:
                    throw new IllegalArgumentException("No mapping.");
            }
            return jdk;
        }
    }
    
    private static final class OkHttp extends HttpClientFacade {
        OkHttp(Implementation impl, int port) {
            super(impl, port);
        }
        
        @Override
        public ResponseFacade<byte[]> getBytes(String path, HttpConstants.Version ver)
                throws IOException
        {
            return execute(GET(path), ver, ResponseBody::bytes);
        }
        
        @Override
        public ResponseFacade<String> getText(String path, HttpConstants.Version ver)
                throws IOException
        {
            return execute(GET(path), ver, ResponseBody::string);
        }
        
        @Override
        public ResponseFacade<String> postAndReceiveText(
                String path, HttpConstants.Version ver, String body)
                throws IOException
        {
            var req = POST(path, RequestBody.create(body, null));
            return execute(req, ver, ResponseBody::string);
        }
        
        @Override
        public ResponseFacade<Void> postBytesAndReceiveEmpty(
                String path, HttpConstants.Version ver, byte[] body)
                throws IOException
        {
            var req = POST(path, RequestBody.create(body, null));
            return execute(req, ver, ResponseBody::bytes)
                    .assertEmpty();
        }
        
        private Request GET(String path) throws MalformedURLException {
            return newRequest("GET", path, null);
        }
        
        private Request POST(String path, RequestBody reqBody) throws MalformedURLException {
            return newRequest("POST", path, reqBody);
        }
        
        private Request newRequest(
                String method, String path, RequestBody reqBody)
                throws MalformedURLException
        {
            var req = new Request.Builder()
                    .method(method, reqBody)
                    .url(withBase(path).toURL());
            copyClientHeaders(req::header);
            return req.build();
        }
        
        private <B> ResponseFacade<B> execute(
                Request req,
                HttpConstants.Version ver,
                IOFunction<? super ResponseBody, ? extends B> rspBodyConverter)
                throws IOException
        {
            var cli= new OkHttpClient.Builder()
                    .protocols(List.of(toSquareVersion(ver)))
                    .build();
            
            // No close callback from our Response type, so must consume eagerly
            var rsp = cli.newCall(req).execute();
            B bdy;
            try (rsp) {
                bdy = rspBodyConverter.apply(rsp.body());
            }
            return ResponseFacade.fromOkHttp(rsp, bdy);
        }
        
        private static Protocol toSquareVersion(HttpConstants.Version ver) {
            final Protocol square;
            switch (ver) {
                case HTTP_0_9:
                case HTTP_3:
                    throw new IllegalArgumentException("Not supported.");
                case HTTP_1_0:
                    square = Protocol.HTTP_1_0;
                    break;
                case HTTP_1_1:
                    square = Protocol.HTTP_1_1;
                    break;
                case HTTP_2:
                    square = Protocol.HTTP_2;
                    break;
                default:
                    throw new IllegalArgumentException("No mapping.");
            }
            return square;
        }
    }
    
    private static final class Apache extends HttpClientFacade {
        Apache(Implementation impl, int port) {
            super(impl, port);
        }
        
        @Override
        public ResponseFacade<byte[]> getBytes(String path, HttpConstants.Version ver)
                throws IOException, InterruptedException, ExecutionException, TimeoutException
        {
            return execute(GET(path, ver), SimpleHttpResponse::getBodyBytes);
        }
        
        @Override
        public ResponseFacade<String> getText(String path, HttpConstants.Version ver)
                throws IOException, ExecutionException, InterruptedException, TimeoutException
        {
            return execute(GET(path, ver), SimpleHttpResponse::getBodyText);
        }
        
        @Override
        public ResponseFacade<String> postAndReceiveText(
                String path, HttpConstants.Version ver, String body)
                throws IOException, InterruptedException, ExecutionException, TimeoutException
        {
            var req = newRequestBuilder("POST", path, ver)
                    .setBody(body, null)
                    .build();
            return execute(req, SimpleHttpResponse::getBodyText);
        }
        
        @Override
        public ResponseFacade<Void> postBytesAndReceiveEmpty(
                String path, HttpConstants.Version ver, byte[] body)
                throws IOException, ExecutionException, InterruptedException, TimeoutException
        {
            var req = newRequestBuilder("POST", path, ver)
                    .setBody(body, null)
                    .build();
            return execute(req, SimpleHttpResponse::getBodyBytes)
                    .assertEmpty();
        }
        
        private SimpleHttpRequest GET(String path, HttpConstants.Version ver) {
            return newRequestBuilder("GET", path, ver).build();
        }
        
        private SimpleRequestBuilder newRequestBuilder(
                String method, String path, HttpConstants.Version ver)
        {
            var b = SimpleRequestBuilder
                    .create(method)
                    .setUri(withBase(path))
                    .setVersion(toApacheVersion(ver));
            copyClientHeaders(b::addHeader);
            return b;
        }
        
        private <B> ResponseFacade<B> execute(
                SimpleHttpRequest req,
                Function<? super SimpleHttpResponse, ? extends B> rspBodyConverter)
                throws IOException, InterruptedException, ExecutionException, TimeoutException
        {
            try (var c = HttpAsyncClients.createDefault()) {
                // Must "start" first, otherwise
                //     java.util.concurrent.CancellationException: Request execution cancelled
                c.start();
                // Same as timeout given to TestClient in BigFileRequestTest
                var rsp = c.execute(req, null).get(5, SECONDS);
                return ResponseFacade.fromApache(rsp, rspBodyConverter.apply(rsp));
            }
        }
        
        private static ProtocolVersion toApacheVersion(HttpConstants.Version ver) {
            return org.apache.hc.core5.http.HttpVersion.get(ver.major(), ver.minor().orElse(0));
        }
        
    }
    
    private static final class Jetty extends HttpClientFacade {
        Jetty(Implementation impl, int port) {
            super(impl, port);
        }
        
        @Override
        public ResponseFacade<byte[]> getBytes(String path, HttpConstants.Version ver)
                throws InterruptedException, ExecutionException, TimeoutException
        {
            return executeReq("GET", path, ver, null,
                    ContentResponse::getContent);
        }
        
        @Override
        public ResponseFacade<String> getText(String path, HttpConstants.Version ver)
                throws InterruptedException, ExecutionException, TimeoutException
        {
            return executeReq("GET", path, ver, null,
                    ContentResponse::getContentAsString);
        }
        
        @Override
        public ResponseFacade<String> postAndReceiveText(
                String path, HttpConstants.Version ver, String body
            ) throws ExecutionException, InterruptedException, TimeoutException
        {
            return executeReq("POST", path, ver,
                    new StringRequestContent(body),
                    ContentResponse::getContentAsString);
        }
        
        @Override
        public ResponseFacade<Void> postBytesAndReceiveEmpty(
                String path, HttpConstants.Version ver, byte[] body)
                throws ExecutionException, InterruptedException, TimeoutException
        {
            return executeReq("POST", path, ver,
                    new BytesRequestContent(body),
                    ContentResponse::getContent)
                        .assertEmpty();
        }
        
        private <B> ResponseFacade<B> executeReq(
                String method, String path,
                HttpConstants.Version ver,
                org.eclipse.jetty.client.api.Request.Content reqBody,
                Function<? super ContentResponse, ? extends B> rspBodyConverter
                ) throws ExecutionException, InterruptedException, TimeoutException
        {
            var c = new org.eclipse.jetty.client.HttpClient();
            
            try {
                c.start();
            } catch (Exception e) { // <-- oh, wow...
                throw new RuntimeException(e);
            }
            
            ContentResponse rsp;
            try {
                var req = c.newRequest(withBase(path))
                           .method(method)
                           .version(toJettyVersion(ver))
                           .body(reqBody);
                
                copyClientHeaders((k, v) ->
                        req.headers(h -> h.add(k, v)));
                
                rsp = req.send();
            } finally {
                try {
                    c.stop();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            
            return ResponseFacade.fromJetty(rsp, rspBodyConverter);
        }
        
        private static org.eclipse.jetty.http.HttpVersion
                toJettyVersion(HttpConstants.Version ver)
        {
            return org.eclipse.jetty.http.HttpVersion.fromString(ver.toString());
        }
    }
    
    private static final class Reactor extends HttpClientFacade {
        Reactor(Implementation impl, int port) {
            super(impl, port);
        }
        
        // On Reactor, the default implementation in HttpClientFacade returns NULL (!), causing NPE.
        // It is conceivable the we'll have to drop support for Reactor, being ridiculous as it is.
        @Override
        public ResponseFacade<Void> getEmpty(String path, HttpConstants.Version ver) {
            return GET(path, ver, null);
        }
        
        @Override
        public ResponseFacade<byte[]> getBytes(String path, HttpConstants.Version ver) {
            return GET(path, ver, ByteBufMono::asByteArray);
        }
        
        @Override
        public ResponseFacade<String> getText(String path, HttpConstants.Version ver) {
            return GET(path, ver, ByteBufMono::asString);
        }
        
        @Override
        public ResponseFacade<String> postAndReceiveText(
                String path, HttpConstants.Version ver, String body)
        {
            return executeReq(
                    io.netty.handler.codec.http.HttpMethod.POST, path, ver,
                    // Erm, yeah, it gets worse...
                    body.length(), ByteBufFlux.fromString(Mono.just(body)),
                    ByteBufMono::asString);
        }
        
        @Override
        public ResponseFacade<Void> postBytesAndReceiveEmpty(
                String path, HttpConstants.Version ver, byte[] body)
        {
            return executeReq(
                    io.netty.handler.codec.http.HttpMethod.POST, path, ver,
                    // "from inbound" hahahahahaha
                    body.length, ByteBufFlux.fromInbound(Mono.just(body)),
                    ByteBufMono::asByteArray)
                        .assertEmpty();
        }
        
        private <B> ResponseFacade<B> GET(
                String path, HttpConstants.Version ver,
                Function<? super ByteBufMono, ? extends Mono<B>> rspBodyConverter)
        {
            return executeReq(
                    io.netty.handler.codec.http.HttpMethod.GET, path, ver,
                    0, null,
                    rspBodyConverter);
        }
        
        private <B> ResponseFacade<B> executeReq(
                io.netty.handler.codec.http.HttpMethod method,
                String path, HttpConstants.Version ver,
                long contentLength,
                org.reactivestreams.Publisher<? extends ByteBuf> reqBody,
                Function<? super ByteBufMono, ? extends Mono<B>> rspBodyConverter)
        {
            var client = reactor.netty.http.client.HttpClient.create()
                    .protocol(toReactorVersion(ver));
            
            for (var entry : clientHeaders()) {
                var name = entry.getKey();
                for (var value : entry.getValue()) {
                    // Yup, you "consume" a mutable object, but... also returns a new client
                    // (who doesn't love a good surprise huh!)
                    client = client.headers(h -> h.add(name, value));
                }
            }
            
            // Otherwise they do chunked encoding; there is no BodyPublisher,
            // sensible default behavior, utility methods, shortcuts, et cetera
            // TODO: Remove whenever we have chunked decoding implemented.
            client = client.headers(h ->
                    h.add("Content-Length", Long.toString(contentLength)));
            
            // "uri() should be invoked before request()" says the JavaDoc.
            // Except that doesn't compile lol
            HttpClient.RequestSender sender
                    = client.request(method)
                            .uri(withBase(path));
            
            // Yes, wildcard
            ResponseReceiver<?> receiver;
            if (reqBody != null) {
                // Because of this
                // (oh, and nothing was quote unquote "sent", btw)
                receiver = sender.send(reqBody);
            } else {
                // Sender is actually a... *drumroll* ....
                receiver = sender;
            }
            
            // Okay seriously, someone give them an award in API design......
            // (compare this method impl with everyone else; even Jetty that
            //  throws Exception is a better option lol)
            if (rspBodyConverter == null) {
                return retype(receiver.response().map(rsp ->
                        ResponseFacade.fromReactor(rsp, null)).block());
            }
            return receiver.responseSingle((head, firstBody) ->
                rspBodyConverter.apply(firstBody).map(anotherBody ->
                        ResponseFacade.fromReactor(head, anotherBody)))
                .block();
        }
        
        private static HttpProtocol toReactorVersion(HttpConstants.Version ver) {
            switch (ver) {
                case HTTP_1_1:
                    return HttpProtocol.HTTP11;
                case HTTP_2:
                    return HttpProtocol.H2;
                default:
                    throw new IllegalArgumentException("No mapping.");
            }
        }
    }
    
    /**
     * A HTTP response API.<p>
     * 
     * Delegates all operations to the underlying client's response
     * implementation (if possible, lazily) without caching.<p>
     * 
     * Two instances are equal only if each operation-pair return equal values
     * (as determined by using {@link Objects#deepEquals(Object, Object)}), or
     * if they both throw two equal {@code UnsupportedOperationException}s. Any
     * other exception will be rethrown from the {@code equals} method.<p>
     * 
     * {@code hashCode} is not implemented.
     * 
     * @param <B> body type
     */
    public static final class ResponseFacade<B> {
        
        static <B> ResponseFacade<B> fromJDK(java.net.http.HttpResponse<? extends B> jdk) {
            Supplier<String> version = () -> HttpConstants.Version.valueOf(jdk.version().name()).toString(),
                             phrase  = () -> {throw new UnsupportedOperationException();};
            return new ResponseFacade<>(
                    version,
                    jdk::statusCode,
                    phrase,
                    jdk::headers,
                    jdk::body);
        }
        
        static <B> ResponseFacade<B> fromOkHttp(okhttp3.Response okhttp, B body) {
            Supplier<HttpHeaders> headers = () -> Headers.of(okhttp.headers().toMultimap());
            return new ResponseFacade<>(
                    () -> okhttp.protocol().toString().toUpperCase(ROOT),
                    okhttp::code,
                    okhttp::message,
                    headers, () -> body);
        }
        
        static <B> ResponseFacade<B> fromApache(SimpleHttpResponse apache, B body) {
            Supplier<HttpHeaders> headers = () -> {
                var exploded = stream(apache.getHeaders())
                        .flatMap(h -> Stream.of(h.getName(), h.getValue()))
                        .toArray(String[]::new);
                return Headers.of(exploded);
            };
            return new ResponseFacade<>(
                    () -> apache.getVersion().toString(),
                    apache::getCode,
                    apache::getReasonPhrase,
                    headers,
                    () -> body);
        }
        
        static <B> ResponseFacade<B> fromJetty(
                org.eclipse.jetty.client.api.ContentResponse jetty,
                Function<? super ContentResponse, ? extends B> bodyConverter)
        {
            Supplier<HttpHeaders> headers = () -> {
                var exploded = jetty.getHeaders().stream()
                        .flatMap(h -> Stream.of(h.getName(), h.getValue()))
                        .toArray(String[]::new);
                
                return Headers.of(exploded);
            };
            return new ResponseFacade<>(
                    () -> jetty.getVersion().toString(),
                    jetty::getStatus,
                    jetty::getReason,
                    headers,
                    () -> bodyConverter.apply(jetty));
        }
        
        static <B> ResponseFacade<B> fromReactor(HttpClientResponse reactor, B body) {
            Supplier<HttpHeaders> headers = () -> {
                var exploded = reactor.responseHeaders().entries().stream()
                        .flatMap(e -> Stream.of(e.getKey(), e.getValue()))
                        .toArray(String[]::new);
                
                return Headers.of(exploded);
            };
            return new ResponseFacade<>(
                    () -> reactor.version().toString(),
                    () -> reactor.status().code(),
                    () -> reactor.status().reasonPhrase(),
                    headers,
                    () -> body);
        }
        
        private final Supplier<String> version;
        private final IntSupplier statusCode;
        private final Supplier<String> reasonPhrase;
        private final Supplier<HttpHeaders> headers;
        private final Supplier<? extends B> body;
        
        private ResponseFacade(
                Supplier<String> version,
                IntSupplier statusCode,
                Supplier<String> reasonPhrase,
                Supplier<HttpHeaders> headers,
                Supplier<? extends B> body)
        {
            this.version      = version;
            this.statusCode   = statusCode;
            this.reasonPhrase = reasonPhrase;
            this.headers      = headers;
            this.body         = body;
        }
        
        /**
         * Returns the HTTP version.
         * 
         * @return the HTTP version
         */
        public String version() {
            return version.get();
        }
        
        /**
         * Returns the response status-code.
         * 
         * @return status-code
         */
        public int statusCode() {
            return statusCode.getAsInt();
        }
        
        /**
         * Returns the response reason-phrase.
         * 
         * @return reason-phrase
         * 
         * @throws UnsupportedOperationException
         *             if the underlying client does not support this operation
         */
        public String reasonPhrase() {
            return reasonPhrase.get();
        }
         
        /**
         * Returns the response headers.
         * 
         * @return headers
         */
        public HttpHeaders headers() {
            return headers.get();
        }
        
        /**
         * Returns the response body.
         * 
         * @return body
         */
        public B body() {
            return body.get();
        }
        
        ResponseFacade<Void> assertEmpty() {
            B b = body();
            if (b == null) {
                return retype(this);
            } else if (b instanceof byte[]) {
                assertThat((byte[]) b).isEmpty();
            } else if (b instanceof CharSequence) {
                assertThat((CharSequence) b).isEmpty();
            } else {
                throw new AssertionError("Unexpected type: " + b.getClass());
            }
            return retype(this);
        }
        
        @Override
        public int hashCode() {
            // Pseudo-implementation to stop compilation warning, which stops the build
            // (equals implemented but not hashCode)
            return super.hashCode();
        }
        
        private static final List<Function<ResponseFacade<?>, ?>> GETTERS = List.of(
                // Add new getters for inclusion into equals() here please
                ResponseFacade::version,
                ResponseFacade::statusCode,
                ResponseFacade::reasonPhrase,
                ResponseFacade::headers,
                ResponseFacade::body );
        
        @Override
        public boolean equals(Object other) {
            if (other == null || other.getClass() != getClass()) {
                return false;
            }
            
            BiFunction<ResponseFacade<?>, Function<ResponseFacade<?>, ?>, ?>
                    getVal = (container, operation) -> {
                            try {
                                return operation.apply(container);
                            } catch (UnsupportedOperationException e) {
                                // This is also considered a value lol
                                return e;
                            }
                    };
            
            var that = (ResponseFacade<?>) other;
            Predicate<Function<ResponseFacade<?>, ?>> check = method ->
                    almostDeepEquals(getVal.apply(this, method), getVal.apply(that, method));
            
            for (var m : GETTERS) {
                if (!check.test(m)) {
                    return false;
                }
            }
            return true;
        }
        
        private boolean almostDeepEquals(Object v1, Object v2) {
            if ((v1 != null && v1.getClass() == UnsupportedOperationException.class) &&
                (v2 != null && v2.getClass() == UnsupportedOperationException.class)) {
                // Same class, compare message
                v1 = ((Throwable) v1).getMessage();
                v2 = ((Throwable) v2).getMessage();
            }
            return deepEquals(v1, v2);
        }
    }
}