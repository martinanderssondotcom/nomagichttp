package alpha.nomagichttp;

import alpha.nomagichttp.message.MediaType;

/**
 * Namespace of constants related to the HTTP protocol.<p>
 * 
 * For values to use in a {@link HeaderKey#CONTENT_TYPE Content-Type} header,
 * see {@link MediaType}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class HttpConstants {
    private HttpConstants() {
        // Empty
    }
    
    /**
     * HTTP methods are included on the first line of a request and indicates
     * the desired action to be performed on a server-side resource. Methods are
     * also known as "verbs", although "OPTIONS" and "HEAD" are not verbs.<p>
     * 
     * Commonly used methods are registered in the
     * <a href="https://www.iana.org/assignments/http-methods">IANA method registry</a>
     * which also features link to RFCs where the methods are defined. The
     * method value is a case-sensitive string and can be anything; originally
     * envisioned as the literal name of an object method in the back-end (
     * <a href="https://tools.ietf.org/html/rfc7231#section-4.1">RFC 7231 §4.1</a>
     * ).<p>
     * 
     * Methods has characteristics. If it is <strong>safe</strong> (
     * <a href="https://tools.ietf.org/html/rfc7231#section-4.2.1">RFC 7231 §4.2.1</a>
     * ), then the request is likely used only for information retrieval and
     * should not have any noticeable side effects on the server (GET, HEAD,
     * OPTIONS and TRACE). If a method is <strong>idempotent</strong> (
     * <a href="https://tools.ietf.org/html/rfc7231#section-4.2.2">RFC 7231 §4.2.2</a>
     * ), then the request may be repeated with no change to the intended effect
     * of the first request (GET, HEAD, PUT, DELETE, OPTIONS and TRACE).
     * Responses to <strong>cacheable</strong> (
     * <a href="https://tools.ietf.org/html/rfc7231#section-4.2.3">RFC 7231 §4.2.3</a>
     * ) requests may be stored for future reuse (GET, HEAD, and POST).<p>
     * 
     * Only {@link #TRACE} requests and responses to {@link #HEAD} have
     * specified semantics that requires a body. For all other methods, the
     * message body is optional. This includes {@link #GET} and {@link #POST} (
     * <a href="https://tools.ietf.org/html/rfc7231#section-4.3.1">RFC 7231 §4.3.1</a>,
     * <a href="https://tools.ietf.org/html/rfc7230#section-3.3.2">RFC 7230 §3.3.2</a>
     * ). The former doesn't usually carry a body with it, and the latter
     * usually do. The NoMagicHTTP server does not enforce an opinion. The
     * request handler is in full control over how it interprets the request and
     * what it responds. For example, you <i>can</i> respond a body to a {@code
     * HEAD} request.<p>
     * 
     * Unless documented otherwise, the NoMagicHTTP server does not treat any
     * method different than the other.
     */
    public static final class Method {
        private Method() {
            // Private
        }
        
        /**
         * Used to retrieve a server resource. The response is usually 200 (OK)
         * with a representation of the resource attached as message body.<p>
         * 
         * The request body doesn't normally have contents.<p>
         * 
         * Safe? Yes. Idempotent? Yes. Response cacheable? Yes.
         * 
         * @see Method
         */
        public static final String GET = "GET";
    
        /**
         * Same as {@link #GET}, except the response will leave out the message
         * body. Used by clients who only have an interest in the response
         * headers, i.e. the resource metadata. For example, to learn when the
         * resource was last modified.<p>
         * 
         * Safe? Yes. Idempotent? Yes. Response cacheable? Yes.
         *
         * @see Method
         */
        public static final String HEAD = "HEAD";
        
        /**
         * Generally, the POST request is the preferred method of transmitting
         * data to a target processor on the server. Often, the request payload
         * represents a new resource to create and the server responds with an
         * {@link HeaderKey#LOCATION Location} header set which identifies the
         * new resource.<p>
         * 
         * For example, posting an element to a collection. In this case, if the
         * request is repeated the collection grows by 1 element each time.
         * 
         * <pre>
         *   -->
         *   POST /user HTTP/1.1
         *   Host: www.example.com
         *   Content-Type: application/json
         *   Content-Length: 31
         *   {"name": "John Doe", "age": 47}
         *   
         *   {@literal <}--
         *   HTTP/1.1 201 Created
         *   Location: /user/123
         *   Content-Length: 0
         * </pre>
         * 
         * Failure to create the resource should probably result in a 4XX
         * (Client Error) or 5XX (Server Error).<p>
         * 
         * It's worthwhile stressing that POST does not have to create a new
         * resource. POST is the general method to use when submitting data in
         * the request body. For example, it could also be used to <i>append</i>
         * records to a log file.<p>
         * 
         * If the client decides what resource identifier a new resource will
         * get, or the client wish to replace all of an existing [known]
         * resource, use {@link #PUT} instead.<p>
         * 
         * Safe? No. Idempotent? No. Response cacheable? Yes.
         * 
         * @see Method
         */
        public static final String POST = "POST";
        
        /**
         * PUT is used by clients to create or replace the state of an already
         * known resource with that of the message payload.<p>
         * 
         * For example, replacing an element in a specific collection. Repeating
         * the request will not grow the collection.<p>
         * 
         * This HTTP exchange example replaces the user created by the example
         * from {@link #POST}:
         * 
         * <pre>
         *   -->
         *   PUT /user/123 HTTP/1.1
         *   Host: www.example.com
         *   Content-Type: application/json
         *   Content-Length: 31
         *   {"name": "New Name", "age": 99}
         * 
         *   {@literal <}--
         *   HTTP/1.1 204 No Content
         *   Content-Length: 0
         * </pre>
         * 
         * If the effect of the request created a new resource which did not
         * exist before, then the server should respond 201 (Created).<p>
         * 
         * If the request wish to update only selected parts of a resource, use
         * {@link #PATCH} instead.<p>
         * 
         * Safe? No. Idempotent? Yes. Response cacheable? No.
         * 
         * @see Method
         */
        public static final String PUT = "PUT";
        
        /**
         * Similar to {@link #PUT}, the PATCH request also updates a known
         * resource except the request body contains a partial update - PUT in
         * contrast, contains the entire modified resource. Another difference
         * is that PATCH is not by definition idempotent, unless the request is
         * made conditional (for example by adding a {@link
         * HeaderKey#IF_MATCH If-Match} header).<p>
         * 
         * Depending on the server endpoint's semantics, a PATCH may just like
         * PUT create a new resource if it didn't already exist. This is
         * probably not very commonly implemented and may from the client's
         * perspective be a confusing and unintended side effect.<p>
         * 
         * The PATCH method is defined in
         * <a href="https://tools.ietf.org/html/rfc5789">RFC 5789</a> which
         * requires the server to execute the patch atomically, but does not
         * specify the syntax of the patch document payload, i.e. how to apply
         * the patch. Two commonly used patch documents are
         * <a href="https://tools.ietf.org/html/rfc6902">RFC 6902 (JSON Patch)</a> and
         * <a href="https://tools.ietf.org/html/rfc7386">RFC 7396 (JSON Merge Patch)</a>
         * . The former being more powerful with embedded operators, the latter
         * being less complex and more easily understood as well as
         * implemented.<p>
         * 
         * Suppose we have a record in our relational database which was created
         * and subsequently edited twice already (version = 3):
         * 
         * <table>
         *   <caption style="display:none">Default Handlers</caption>
         *   <thead>
         *     <tr>
         *       <th>ID</th>
         *       <th>Name</th>
         *       <th>Age</th>
         *       <th>Version</th>
         *     </tr>
         *   </thead>
         *   <tbody>
         *     <tr>
         *       <td>123</td>
         *       <td>John Doe</td>
         *       <td>47</td>
         *       <td>3</td>
         *     </tr>
         *   </tbody>
         * </table><p>
         * 
         * The following example of an HTTP exchange uses JSON Merge Patch to
         * change John's name:
         * 
         * <pre>
         *   -->
         *   PATCH /user/123 HTTP/1.1
         *   Host: www.example.com
         *   If-Match: "3"
         *   Content-Type: application/merge-patch+json
         *   Content-Length: 20
         *   {"name": "New Name"}
         * 
         *   {@literal <}--
         *   HTTP/1.1 204 No Content
         *   Content-Length: 0
         * </pre>
         * 
         * The JSON Patch equivalent request could skip the {@code If-Match}
         * header and test the name value instead:
         * 
         * <pre>
         *   PATCH /user/123 HTTP/1.1
         *   Host: www.example.com
         *   Content-Type: application/json-patch+json
         *   Content-Length: 121
         *   [
         *     { "op": "test", "path": "/name", "value": "John Doe" },
         *     { "op": "replace", "path": "/name", "value": "New Name" }
         *   ]
         * </pre>
         * 
         * Safe? No. Idempotent? No. Response cacheable? No.
         */
        public static final String PATCH = "PATCH";
        
        /**
         * Used by clients to remove [the public visibility of-] or delete (like
         * permanently; reclaim disk space and shit) a server-side resource.<p>
         * 
         * The request body doesn't normally have contents. The contents of the
         * response body is very dependent on the nature of the resource and
         * operation. For example, <i>remove</i> is probably more likely than
         * <i>delete</i> to return the resource, perhaps limited by resource
         * size.<p>
         * 
         * It is not defined how the operation cascade to multiple
         * representations/content-types (
         * <a href="https://tools.ietf.org/html/rfc7231#section-4.3.5">RFC 7231 §4.3.5</a>
         * ).<p>
         * 
         * Safe? No. Idempotent? Yes. Response cacheable? No.
         */
        public static final String DELETE = "DELETE";
        
        /**
         * Used by client-to-proxy or proxy-to-proxy for establishing a tunnel.
         * Most origin servers have no use of this method and doesn't implement
         * it.<p>
         * 
         * The request body doesn't normally have contents.<p>
         * 
         * Safe? No. Idempotent? No. Response cacheable? No.
         */
        public static final String CONNECT = "CONNECT";
        
        /**
         * Used by client to probe the capabilities of a server. The request
         * target may be an asterisk ("*") and applies then to the entire server
         * in general. And that's pretty much all what is defined in
         * <a href="https://tools.ietf.org/html/rfc7231#section-4.3.7">RFC 7231 §4.3.7</a>
         * . Specifically, whether or not the request- and response bodies have
         * content is not defined.<p>
         * 
         * In the following HTTP exchange, the client discovers what methods are
         * allowed for the user identified as 123:
         * 
         * <pre>
         *   -->
         *   OPTIONS /user/123 HTTP/1.1
         *   Host: www.example.com
         *   Content-Length: 0
         * 
         *   {@literal <}--
         *   HTTP/1.1 204 No Content
         *   Allow: OPTIONS, GET, HEAD, PATCH
         *   Content-Length: 0
         * </pre>
         * 
         * Future work is scheduled to have the NoMagicHTTP server automate the
         * discovery of allowed methods.<p>
         * 
         * Ping-pong:
         * 
         * <pre>
         *   -->
         *   OPTIONS * HTTP/1.1
         *   Host: www.example.com
         *   Content-Length: 0
         * 
         *   {@literal <}--
         *   HTTP/1.1 200 OK
         *   Content-Length: 4
         *   PONG
         * </pre>
         * 
         * Safe? Yes. Idempotent? Yes. Response cacheable? No.
         */
        public static final String OPTIONS = "OPTIONS";
        
        /**
         * Used by clients to discover the chain of intermediaries that an HTTP
         * message passes through. The final recipient ought to reply the
         * message received. The client can then inspect what was observed on
         * the other side, specifically the {@link HeaderKey#VIA Via} header.
         * The server should exclude sensitive data from the response, such as
         * cookies with user credentials.<p>
         * 
         * Due to the security risk of revealing sensitive data, some HTTP
         * applications disable the TRACE method. Future work is scheduled to
         * have this option available in the NoMagicHTTP's configuration as
         * well. The NoMagicHTTP server certainly has no automatic response
         * behavior related to this method and most likely never will.<p>
         * 
         * Safe? Yes. Idempotent? Yes. Response cacheable? No.
         */
        public static final String TRACE = "TRACE";
    }
    
    /**
     *
     */
    public static final class StatusCode {
        private StatusCode() {
            // Private
        }
    }
    
    /**
     *
     */
    public static final class ReasonPhrase {
        private ReasonPhrase() {
            // Private
        }
    }
    
    /**
     *
     */
    public static final class HeaderKey {
        private HeaderKey() {
            // Private
        }
        
        public static final String LOCATION = "Location";
    
        public static final String CONTENT_TYPE = "Content-Type";
        
        public static final String IF_MATCH = "If-Match";
    
        public static final String VIA = "Via";
    }
    
    /**
     *
     */
    public static final class Version {
        private Version() {
            // Private
        }
    }
}