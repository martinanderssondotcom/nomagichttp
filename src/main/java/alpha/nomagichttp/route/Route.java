package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.Request;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * A {@code Route} is "a target resource upon which to apply semantics"
 * (<a href="https://tools.ietf.org/html/rfc7230#section-5.1">RFC 7230 §5.1</a>).
 * It can be built using a {@link #builder(String)} or other static methods
 * found in {@link Routes}.<p>
 * 
 * The route is associated with one or more <i>request handlers</i>. In HTTP
 * parlance, handlers are also known as different "representations" of the
 * resource. Which handler specifically to invoke for the request is determined
 * by qualifying metadata and specificity, as detailed in the Javadoc of {@link
 * RequestHandler}.<p>
 * 
 * Suppose the HTTP server receives this request (
 * <a href="https://tools.ietf.org/html/rfc7230#section-5.3.1">RFC 7230 §5.3.1</a>):<p>
 * <pre>{@code
 *   GET /where?q=now HTTP/1.1
 *   Host: www.example.com
 * }</pre>
 * 
 * The request-target "/where?q=now" has a <i>path</i> component and a query
 * component. The path "/where" will match a route in the HTTP server which
 * declares exactly one segment "where". The query "?q=now" specifies a
 * "q"-named parameter with the value "now".<p>
 * 
 * The route may declare named path parameters which act like a wildcard segment
 * whose dynamic value is given by the client through the request path. Both
 * query- and path parameters are optional and they can not be specified as
 * required. Their values may be retrieved using {@link Request#parameters()}<p>
 * 
 * Using curly braces ("{}") syntax for notation purposes only, suppose we have
 * a route made up of two segments and two path parameters:
 * 
 * <pre>
 *   /users/{user-id}/items/{item-id}
 * </pre>
 * 
 * This route will be a match for all of the following request paths:
 * 
 * <pre>
 *   /users/items
 *   /users/123/items
 *   /users/items/456
 *   /users/123/items/456
 * </pre>
 * 
 * The only difference between these request paths is which parameter values
 * will be present in the request object.<p>
 * 
 * Route collision- and ambiguity is detected at build-time and will fail-fast
 * with a {@link RouteCollisionException}. For example, the route {@code
 * "/where"} can not be added to an HTTP server which already has {@code
 * "/where/{param}"} registered (parameters are optional).<p>
 * 
 * In order to find a matching route, the following steps are applied to the
 * request path:
 * 
 * <ul>
 *   <li>Clustered forward slashes are reduced to just one. Empty segments are
 *       not supported and will consequently be discarded.</li>
 *   <li>All trailing forward slashes are truncated. A trailing slash is usually
 *       used to separate a file from a directory. However, "R" in URI stands
 *       for <i>resource</i>. Be that a file, directory, whatever - makes no
 *       difference.</li>
 *   <li>The empty path will be replaced with "/".</li>
 *   <li>The path is split into segments using the forward slash character as a
 *       separator.</li>
 *   <li>Each segment will be percent-decoded as if using {@link
 *       URLDecoder#decode(String, Charset) URLDecoder.decode(segment, StandardCharsets.UTF_8)}
 *       <i>except</i> the plus sign ('+') is <i>not</i> converted to a space
 *       character and remains the same (standard
 *       <a href="https://tools.ietf.org/html/rfc3986#section-2.1">RFC 3986</a> behavior).</li>
 *   <li>Dot-segments (".", "..") are normalized as defined by step 1 and 2 in
 *       Javadoc of {@link URI#normalize()} (basically "." is removed and ".."
 *       removes the previous segment)</li>
 *   <li>Finally, all remaining segments that are not interpreted as a path
 *       parameter value must match a route's segments exactly and in order. In
 *       particular, note that route-matching is case-sensitive and characters
 *       such as "+" and "*" has no special meaning, they will be compared
 *       literally.</li>
 * </ul>
 * 
 * The implementation is thread-safe.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see RouteRegistry
 * @see Route.Builder
 * @see Request.Parameters
 */
public interface Route
{
    /**
     * Returns a {@code Route} builder.<p>
     * 
     * The most simplest route that can be built is the root without path
     * parameters:
     * 
     * <pre>{@code
     *     Route r = Route.builder("/").handler(...).build();
     * }</pre>
     * 
     * Alternatively, import static {@code Routes.route()}, then:
     * <pre>{@code
     *     Route r = route("/", ...).build();
     * }</pre>
     * 
     * Please note that the value given to this method as well as the {@link
     * Builder#append(String)} method may in turn contain many segments. These
     * calls are equivalent:
     * 
     * <pre>{@code
     *    Route.builder("/a").append("/b")...
     *    Route.builder("/a/b")...
     * }</pre>
     * 
     * @param segment initial seed (may be a single forward slash character)
     * 
     * @return a new builder
     * 
     * @throws NullPointerException
     *             if {@code segment} is {@code null}
     * 
     * @throws IllegalArgumentException
     *             see {@link Route.Builder}
     */
    static Route.Builder builder(String segment) {
        return new DefaultRoute.Builder(segment);
    }
    
    /**
     * Returns a match if this route matches the specified {@code requestTarget},
     * otherwise {@code null}.<p>
     * 
     * If there is no such route registered with the HTTP server, a {@link
     * NoRouteFoundException} is thrown, which is translated by {@link
     * ErrorHandler#DEFAULT} into a "404 Not Found" response.<p>
     * 
     * The request-target passed to this method must have the trailing query
     * part - if present - cut off.<p>
     * 
     * The HTTP server does not interpret the fragment part and it is undefined
     * whether or not it is included as part of the given request-target. The
     * fragment "is dereferenced solely by the user agent" (<a
     * href="https://tools.ietf.org/html/rfc3986#section-3.5">RFC 3986 §3.5</a>)
     * and so shouldn't have been sent to the server in the first place.
     * 
     * @param requestTarget  request-target
     * 
     * @return a match if this route matches the specified {@code requestTarget},
     *         otherwise {@code null}
     * 
     * @see RequestHandler
     */
    // TODO: See JavaDoc. Define behavior more exact. Also rename parameter to "path".
    // https://tools.ietf.org/html/rfc3986#section-3.4
    // TODO: HTTP server split RT into segments and can then walk a tree of routes to find the match.
    @Deprecated // To be removed
    Match matches(String requestTarget);
    
    /**
     * Lookup a handler given a specified {@code method} and media types.
     * 
     * @param method       method ("GET", "POST", ...)
     * @param contentType  "Content-Type: " header value (may be {@code null})
     * @param accepts      "Accept: " header values (may be {@code null} or empty)
     * 
     * @return the handler
     * 
     * @throws NullPointerException
     *             if {@code method} is {@code null}
     * 
     * @throws NoHandlerFoundException
     *             if no handler matching the criteria can be found
     */
    RequestHandler lookup(String method, MediaType contentType, MediaType[] accepts);
    
    /**
     * Returns the route identity.
     * 
     * @return the route identity (never {@code null} or empty)
     * 
     * @see Route
     */
    @Deprecated // To be removed
    String identity();
    
    /**
     * Returns all segments joined with named parameter values.<p>
     * 
     * Path parameter names will be enclosed within "/{}".<p>
     * 
     * For example, if route has segment "/A" + "my-param" + segment "/B", then
     * the returned String will be "/A/{my-param}/B".
     * 
     * @return all segments joined with named parameter values
     */
    @Override
    String toString();
    
    /**
     * A route matched against a request.
     */
    @Deprecated // To be deleted
    interface Match {
        /**
         * Returns the matched route.<p>
         * 
         * The returned reference is the same object as the one invoked to
         * produce the match.
         * 
         * @return the matched route
         */
        Route route();
        
        /**
         * Returns path parameters which have been extracted from the
         * request-target.<p>
         * 
         * The returned map is empty if the route has no path parameters
         * declared or none was provided in the request-target.<p>
         * 
         * The returned map is unmodifiable.
         * 
         * @return path parameters (never null)
         */
        Map<String, String> parameters();
    }
    
    /**
     * Builder of a {@link Route}.<p>
     * 
     * Example: 
     * <pre>{@code 
     *     Route r = Route.builder("/users")
     *                    .param("user-id")
     *                    .append("/items")
     *                    .param("item-id")
     *                    .handler(...)
     *                    .build();
     *     
     *     String s = r.toString(); // "/users/{user-id}/items/{item-id}"
     * }</pre>
     * 
     * Segment values provided to a builder is validated accordingly:
     * <ul>
     *   <li>Must start with a forward slash character ('/').</li>
     *   <li>Only the root may also end with a forward slash character (see
     *       {@link Route#builder(String)}).</li>
     *   <li>Only the root can be a string whose content is a single
     *       forward slash. All other segment values must have content
     *       following the forward slash.</li>
     *   <li>Can not contain a forward slash following another forward slash
     *       (empty segments not supported, see {@link Route}). The segment may
     *       be comprised of other segments, for example "/a/b/c".</li>
     * </ul>
     * 
     * A specified invalid segment value will cause the builder to throw an
     * {@code IllegalArgumentException}.<p>
     * 
     * A valid parameter name is any string, even the empty string. The only
     * requirement is that it has to be unique for the route. The HTTP server's
     * chief purpose of the name is to use it as a key in a parameter map data
     * structure. Please note that the name is specified to participate in the
     * {@link Route#toString()} result.<p>
     * 
     * The builder is not thread-safe and is intended to be used as a throw-away
     * object. Each of the setter methods modifies the state of the builder and
     * returns the same instance.<p>
     * 
     * The implementation does not necessarily implement {@code hashCode()} and
     * {@code equals()}.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     * 
     * @see Route
     */
    interface Builder
    {
        /**
         * Declare one or many named path parameters.
         * 
         * @param firstName  first name
         * @param moreNames  optionally more names
         * 
         * @return this (for chaining/fluency)
         * 
         * @throws NullPointerException
         *             if anyone of the provided names is {@code null}
         * 
         * @throws IllegalStateException
         *             if an equivalent parameter name has already been added
         */
        Route.Builder param(String firstName, String... moreNames);
        
        /**
         * Append another segment.
         * 
         * @param segment to append
         * 
         * @return this (for chaining/fluency)
         * 
         * @throws NullPointerException
         *             if {@code segment} is {@code null}
         * 
         * @throws IllegalArgumentException
         *             see {@link Route.Builder}
         */
        Route.Builder append(final String segment);
        
        /**
         * Add a request handler.
         * 
         * @param first  first request handler
         * @param more   optionally more handlers
         * 
         * @throws HandlerCollisionException
         *             if an equivalent handler has already been added
         * 
         * @return this (for chaining/fluency)
         * 
         * @see RequestHandler
         */
        Route.Builder handler(RequestHandler first, RequestHandler... more);
        
        /**
         * Returns a new {@code Route} built from the current state of this
         * builder.
         * 
         * @return a new {@code Route}
         * 
         * @throws IllegalStateException
         *             if no handlers have been added
         */
        Route build();
    }
}