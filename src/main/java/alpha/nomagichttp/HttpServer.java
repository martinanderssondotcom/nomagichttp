package alpha.nomagichttp;

import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.internal.DefaultServer;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.route.DefaultRouteRegistry;
import alpha.nomagichttp.route.HandlerCollisionException;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.RouteCollisionException;
import alpha.nomagichttp.route.RouteParseException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.util.concurrent.CompletionStage;

/**
 * Listens on a port for HTTP {@link Request requests} targeting a specific
 * {@link Route route} which contains at least one {@link RequestHandler request
 * handler} which processes the request into a {@link Response response}.<p>
 * 
 * This interface declares static <i>{@code create}</i> methods that construct
 * and return the default implementation {@link DefaultServer}. Once the server
 * has been constructed, it needs to <i>{@code start()}</i>.<p>
 * 
 * Routes can be dynamically added and removed using {@link #add(Route)} and
 * {@link #remove(Route)}.<p>
 * 
 * The server's function is to provide port- and channel management, parse
 * an inbound request head and resolve which handler of a route is qualified to
 * handle the request. Once the handler has been invoked, it has total freedom
 * in regards to how it interprets the request headers- and body as well as what
 * headers and body it responds.
 * 
 * 
 * <h2>Server Life-Cycle</h2>
 * 
 * It is possible to start many server instances on different ports. One
 * use-case for this pattern is to expose public endpoints on one port but
 * more sensitive administrator endpoints on another more secluded port.<p>
 * 
 * If at least one server is running, then the JVM will not shutdown when the
 * main application thread dies. For the application process to end, all
 * server instances must {@link #stop()}.<p>
 * 
 * The server may be recycled, i.e. started anew after having been stopped, any
 * number of times.
 * 
 * 
 * <h2>Supported HTTP Versions</h2>
 *
 * Currently, the NoMagicHTTP server is a project in its infancy. Full support
 * HTTP/1.0 and 1.1 is the first milestone, yet to be completed (see POA.md in
 * repository). HTTP/2 will be implemented thereafter. HTTP clients older than
 * HTTP/1.0 is rejected (exchange will crash with a {@link
 * HttpVersionTooOldException}.
 * 
 * 
 * <h2>HTTP message semantics</h2>
 * 
 * Only a very few message variants are specified to <i>not</i> have a body and
 * will be rejected by the server if they do:
 * 
 * <ul>
 *   <li>{@link HttpConstants.Method#TRACE TRACE} requests (
 *     <a href="https://tools.ietf.org/html/rfc7231#section-4.3.8">RFC 7231 §4.3.8</a>)</li>
 *   <li>Responses to {@link HttpConstants.Method#HEAD HEAD} (
 *     <a href="https://tools.ietf.org/html/rfc7231#section-4.3.2">RFC 7231 §4.3.8</a>)
 *     and {@link HttpConstants.Method#CONNECT CONNECT} (
 *     <a href="https://tools.ietf.org/html/rfc7231#section-4.3.6">RFC 7231 §4.3.6</a>)</li>
 *   <li>Responses with a 1XX (Informational) {@link HttpConstants.StatusCode status code} (
 *     <a href="https://tools.ietf.org/html/rfc7231#section-6.2">RFC 7231 §6.2</a>)</li>
 * </ul>
 * 
 * These variants are rejected before or after the handler has been called,
 * depending on whether the message is a request or a response (TODO: define
 * exc types). They <i>must</i> be rejected since including a body would kill
 * the protocol. For example, a new virtual connection/protocol behavior is
 * supposed to start after the response headers to a successful {@code CONNECT}
 * request and {@code 101 (Switching Protocol)} response.<p>
 * 
 * For all other variants of requests and responses, the body is optional and
 * the server does not reject the message nor does the API enforce an
 * opinionated view. This is also true for message components such as the
 * response status code and reason phrase. The request handler is in full
 * control over how it interprets the request message and what response it
 * returns.
 * 
 * For example, it might not be common but it <i>is</i>
 * possible (and legit) for {@link HttpConstants.Method#GET GET} requests (
 * <a href="https://tools.ietf.org/html/rfc7231#section-4.3.1">RFC 7231 §4.3.1</a>
 * ) to have a body and for {@link HttpConstants.Method#POST POST} responses to
 * not have a body (
 * <a href="https://tools.ietf.org/html/rfc7230#section-3.3.2">RFC 7230 §3.3.2</a>
 * ). Similarly, the {@link HttpConstants.StatusCode#TWO_HUNDRED_ONE 201
 * (Created)} response often do have a body which "typically describes and links
 * to the resource(s) created" (
 * <a href="https://tools.ietf.org/html/rfc7231#section-6.3.2">RFC 7231 §6.3.2</a>
 * ), but it's not required to. And so the list goes on.
 * 
 * 
 * <h2>Thread Safety and Threading Model</h2>
 * 
 * The server is fully thread-safe, and mostly, asynchronous and
 * non-blocking.<p>
 * 
 * Life-cycle methods {@code start} and {@code stop} may block temporarily
 * and should understandably not be invoked at a high rate.<p>
 * 
 * The HttpServer API also functions as a route registry, to which we {@code
 * add} and {@code remove} routes. These methods are highly concurrent but may
 * impose minuscule blocks at the discretion of the implementation. Most
 * importantly, looking up a route - as is done on every inbound request - never
 * blocks and features great performance no matter the size of the registry.<p>
 * 
 * All servers running in the same JVM share a common pool of threads (aka
 * "request threads"). The pool handles I/O completion events and executes
 * application-provided entities such as the request- and error handlers. The
 * pool size is fixed and set to the value of {@link Config#threadPoolSize()} at
 * the time of the start of the first server instance.<p>
 * 
 * It is absolutely crucial that the application does not block a request
 * thread, for example by synchronously waiting on an I/O result. The request
 * thread is suitable only for short-lived and CPU-bound work. I/O work or
 * long-lived tasks should execute somewhere else. Blocking the request thread
 * will have a negative impact on scalability and could at worse starve the pool
 * of available threads making the server unable to make progress with tasks
 * such as accepting new client connections or processing other requests.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Route
 * @see RequestHandler
 * @see ErrorHandler
 */
public interface HttpServer
{
    /**
     * Create a server using {@linkplain Config#DEFAULT default
     * configuration}.<p>
     * 
     * The provided array of error handlers will be copied as-is into a {@code
     * List}. Application should make sure the array does not contain
     * duplicates, unless it for some bizarre reason wish to have an error
     * handler called multiple times.
     * 
     * @param eh error handler(s)
     * 
     * @return an instance of {@link DefaultServer}
     * 
     * @throws NullPointerException
     *             if {@code eh} or an element therein is {@code null}
     */
    static HttpServer create(ErrorHandler... eh) {
        return create(Config.DEFAULT, eh);
    }
    
    /**
     * Create a server.<p>
     * 
     * The provided array of error handlers will be copied as-is into a {@code
     * List}. Application should make sure the array does not contain
     * duplicates, unless it for some bizarre reason wish to have an error
     * handler called multiple times.
     * 
     * @param config of server
     * @param eh     error handler(s)
     * 
     * @return an instance of {@link DefaultServer}
     * 
     * @throws NullPointerException
     *             if any given argument or element is {@code null}
     */
    static HttpServer create(Config config, ErrorHandler... eh) {
        return new DefaultServer(config, new DefaultRouteRegistry(), eh);
    }
    
    /**
     * Make the server listen for new client connections on a system-picked port
     * on the loopback address (IPv4 127.0.0.1, IPv6 ::1).<p>
     * 
     * This method is useful for inter-process communication on the same machine
     * or to start a server in a test environment.<p>
     * 
     * The port can be retrieved using {@link #getLocalAddress()}{@code
     * .getPort()}.
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>{@code
     *     InetAddress addr = InetAddress.getLoopbackAddress();
     *     int port = 0;
     *     SocketAddress local = new InetSocketAddress(addr, port);
     *     return start(local);
     * }</pre>
     * 
     * @return a bound server-socket channel
     * 
     * @throws IllegalStateException if the server is already running
     * @throws IOException if an I/O error occurs
     * 
     * @see InetAddress
     */
    
    default HttpServer start() throws IOException {
        return start(null);
    }
    
    /**
     * Make the server listen for new client connections on the specified port
     * on the wildcard address (also known as "any local address" and "the
     * unspecified address").
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>{@code
     *     return start(new InetSocketAddress(port));
     * }</pre>
     * 
     * @param port to use
     * 
     * @return this (for chaining/fluency)
     * 
     * @throws IllegalStateException if server is already running
     * @throws IOException if an I/O error occurs
     * 
     * @see InetAddress
     */
    
    default HttpServer start(int port) throws IOException  {
        return start(new InetSocketAddress(port));
    }
    
    /**
     * Make the server listen for new client connections on the specified
     * hostname and port.
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>{@code
     *     return start(new InetSocketAddress(hostname, port));
     * }</pre>
     * 
     * @param hostname to use
     * @param port to use
     * 
     * @return this (for chaining/fluency)
     * 
     * @throws IllegalStateException if server is already running
     * @throws IOException if an I/O error occurs
     * 
     * @see InetAddress
     */
    default HttpServer start(String hostname, int port) throws IOException {
        return start(new InetSocketAddress(hostname, port));
    }
    
    /**
     * Make the server listen for new client connections on the specified
     * address.
     * 
     * Passing in {@code null} for address is equivalent to {@link #start()}
     * without any arguments, i.e. a system-picked port will be used on the
     * loopback address.
     * 
     * @param address to use
     * 
     * @return this (for chaining/fluency)
     * 
     * @throws IllegalStateException if server is already running
     * @throws IOException if an I/O error occurs
     *
     * @see InetAddress
     */
    HttpServer start(SocketAddress address) throws IOException;
    
    /**
     * Stop the server.<p>
     * 
     * The server's listening port will be immediately closed and then this
     * method returns. All active HTTP exchanges will be allowed to complete
     * before the returned stage completes with {@code null}.<p>
     * 
     * If the server was just started and is still in the midst of opening the
     * server's listening port, then this method will block until the startup
     * routine is completed before initiating the shutdown.<p>
     * 
     * If the server is not running then the returned stage is already
     * completed.<p>
     * 
     * Upon failure to close the server's listening port, the stage will
     * complete exceptionally with an {@code IOException}.<p>
     * 
     * It is possible for the server to be started up again whilst a stage has
     * been returned from this method but active HTTP exchanges have yet to
     * complete. In this case, the returned stage will not complete until
     * earliest at the next server stop.<p>
     * 
     * The returned stage is a defensive copy and can not be used to abort the
     * shutdown.
     * 
     * There are no locks involved between a server's start and the completion
     * of the returned stage. If the application starts the same server
     * concurrent to the completion of the last HTTP exchange from the previous
     * run cycle, then technically it is possible for the returned stage to
     * complete at the same time the server is in a running state.<p>
     * 
     * @return the result
     */
    CompletionStage<Void> stop();
    
    /**
     * Stop the server and all HTTP exchanges, now.<p>
     * 
     * The server's listening port will be immediately closed and then all
     * active HTTP exchanges will be aborted. Once all HTTP exchanges have
     * finished, this method returns.<p>
     * 
     * If the server was just started and is still in the midst of opening the
     * server's listening port, then this method will block until the startup
     * routine is completed before initiating the shutdown.
     * 
     * @throws IOException if an I/O error occurs
     */
    void stopNow() throws IOException;
    
    /**
     * Returns {@code true} if the server is running, otherwise {@code false}.<p>
     * 
     * By running means that the server has completed a startup, and has also
     * not completed a subsequent closure of the server's listening port. This
     * method answers the question; is the server listening on a port?<p>
     * 
     * The method does not take into account the state of lingering HTTP
     * exchanges and/or the state of their underlying children channels.<p>
     * 
     * This method does not block.
     * 
     * @return {@code true} if the server is running, otherwise {@code false}
     */
    boolean isRunning();
    
    /**
     * Build a route and add it to the server.
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>
     *     Route r = {@link Route}.{@link Route#builder(String)
     *               builder}(pattern).{@link Route.Builder#handler(RequestHandler, RequestHandler...)
     *               handler}(first, more).{@link Route.Builder#build()
     *               build}();
     *     return add(r);
     * </pre>
     * 
     * @param pattern of route path
     * @param first   request handler
     * @param more    optionally more request handlers
     * 
     * @return {@code this} (for chaining/fluency)
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * 
     * @throws RouteParseException
     *             if a static segment value is empty, or
     *             if parameter names are repeated in the pattern, or
     *             if a catch-all parameter is not the last segment
     * 
     * @throws HandlerCollisionException
     *             if not all handlers are unique
     * 
     * @throws RouteCollisionException
     *             if an equivalent route has already been added
     */
    default HttpServer add(String pattern, RequestHandler first, RequestHandler... more) {
        Route r = Route.builder(pattern).handler(first, more).build();
        return add(r);
    }
    
    /**
     * Add a route.
     * 
     * @param  route to add
     * @return {@code this} (for chaining/fluency)
     * 
     * @throws NullPointerException
     *             if {@code route} is {@code null}
     * 
     * @throws RouteCollisionException
     *             if an equivalent route has already been added
     * 
     * @see Route
     */
    HttpServer add(Route route);
    
    /**
     * Remove a route.<p>
     * 
     * This method is similar to {@link #remove(Route)}, except any route no
     * matter its identity found at the hierarchical position will be removed.
     * The pattern provided is the same path-describing pattern provided to
     * methods such as {@link #add(String, RequestHandler, RequestHandler...)}
     * and {@link Route#builder(String)}, except path parameter names can be
     * anything, they simply do not matter. Other than that, the pattern will go
     * through the same normalization and validation routine.<p>
     * 
     * For example:
     * <pre>{@code
     *   server.add("/download/:user/*filepath", ...);
     *   server.remove("/download/:/*"); // or "/download/:bla/*bla", doesn't matter
     * }</pre>
     * 
     * @param pattern of route to remove
     * 
     * @return the route removed ({@code null} if non-existent)
     * 
     * @throws IllegalArgumentException
     *             if a static segment value is empty
     * 
     * @throws IllegalStateException
     *             if a catch-all parameter is not the last segment
     */
    Route remove(String pattern);
    
    /**
     * Remove a route of a particular identity.<p>
     * 
     * The route's currently active requests and exchanges will run to
     * completion and will not be aborted. Only when all active connections
     * against the route have closed will the route effectively not be in use
     * anymore. However, the route is guaranteed to not be <i>discoverable</i>
     * for <i>new</i> lookup operations once this method has returned.<p>
     * 
     * In order for the route to be removed, the current route in the registry
     * occupying the same hierarchical position must be {@code equal} to the
     * given route using {@code Route.equals(Object)}. Currently, route equality
     * is not specified and the default implementation has not overridden the
     * equals method. I.e., the route provided must be the same instance.<p>
     * 
     * In order to remove <i>any</i> route at the targeted position, use {@link
     * #remove(String)} instead.
     * 
     * @param route to remove
     * 
     * @return {@code true} if successful, otherwise {@code false}
     * 
     * @throws NullPointerException if {@code route} is {@code null}
     */
    boolean remove(Route route);
    
    /**
     * Returns the server's configuration.
     *
     * @return the server's configuration (never {@code null})
     */
    Config getConfig();
    
    /**
     * Returns the socket address that the server is listening on.<p>
     * 
     * If the server was just started and is still in the midst of opening the
     * server's listening port, then this method will block until the startup
     * routine is completed before returning.
     * 
     * @return the port used by the server
     * 
     * @throws IllegalStateException if server is not running
     * @throws IOException if an I/O error occurs
     * 
     * @see AsynchronousServerSocketChannel#getLocalAddress() 
     */
    InetSocketAddress getLocalAddress() throws IllegalStateException, IOException;
    
    /**
     * Server configuration.<p>
     * 
     * The implementation is thread-safe.<p>
     * 
     * The implementation used if none is specified is {@link #DEFAULT}.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com
     */
    interface Config
    {
        /**
         * Values used:<p>
         * 
         * Max request head size = 8 000 <br>
         * Max error recovery attempts = 5 <br>
         * Thread pool-size = {@code Runtime.getRuntime().availableProcessors()}
         */
        Config DEFAULT = new Config(){};
        
        /**
         * Returns the max number of bytes processed while parsing a request
         * head before giving up.<p>
         * 
         * Once the limit has been exceeded, a {@link
         * MaxRequestHeadSizeExceededException} will be thrown.<p>
         * 
         * This configuration value will be polled at the start of each new request.
         * 
         * @implSpec
         * The default implementation is equivalent to:
         * <pre>{@code
         *     return 8_000;
         * }</pre>
         * 
         * This corresponds to <a
         * href="https://tools.ietf.org/html/rfc7230#section-3.1.1">section 3.1.1 in
         * RFC 7230</a> as well as <a
         * href="https://stackoverflow.com/a/8623061/1268003">common practice</a>.
         * 
         * @return number of request head bytes processed before exception is thrown
         */
        default int maxRequestHeadSize() {
            return 8_000;
        }
        
        /**
         * Returns the max number of attempts at recovering a failed request.<p>
         * 
         * This configuration has an effect only if the application has provided one
         * or more error handlers to the server.<p>
         * 
         * When all tries have been exhausted, the {@link ErrorHandler#DEFAULT
         * default error handler} will be called with the original exception.<p>
         * 
         * Successfully invoking an error handler (handler returns a response or
         * throws a <i>different</i> exception instance) counts as one attempt.<p>
         * 
         * The configuration value will be polled at the start of each recovery
         * attempt.
         * 
         * @implSpec
         * The default implementation returns {@code 5}.
         * 
         * @return max number of attempts
         * 
         * @see ErrorHandler
         */
        default int maxErrorRecoveryAttempts() {
            return 5;
        }
        
        /**
         * Returns the number of request threads that should be allocated for
         * executing HTTP exchanges (such as calling the application-provided
         * request- and error handlers).<p>
         * 
         * For a runtime change of this value to have an effect, all server
         * instances must restart.
         * 
         * @implSpec
         * The default implementation returns {@link Runtime#availableProcessors()}.
         * 
         * @return thread pool size
         */
        default int threadPoolSize() {
            return Runtime.getRuntime().availableProcessors();
        }
        
        /**
         * Reject HTTP/1.0 clients, yes or no.<p>
         * 
         * By default, this method returns {@code false} and the server will
         * therefore <i>accept</i> HTTP/1.0 clients.<p>
         * 
         * Rejection takes places through a server-thrown {@link
         * HttpVersionTooOldException} which by default gets translated to a
         * "426 Upgrade Required" response.<p>
         * 
         * Apart from not having all HTTP/1.1 features available for the
         * exchange, HTTP/1.0 does not by default support persistent connections
         * and may as a consequence be a wasteful protocol.<p>
         * 
         * In order to minimize waste, it's recommended to override this value
         * with {@code true}. As a library however, we have to be backwards
         * compatible and support as many applications as possible "out of the
         * box", hence the {@code false} default.<p>
         * 
         * The configuration value will be polled at the beginning of each HTTP
         * exchange.<p>
         * 
         * Note that HTTP/0.9 or older clients are always rejected (can not be
         * configured differently).
         * 
         * @implSpec
         * The default implementation returns {@code false}.
         * 
         * @return whether or not to reject HTTP/1.0 clients
         */
        default boolean rejectClientsUsingHTTP1_0() {
            return false;
        }
    }
}