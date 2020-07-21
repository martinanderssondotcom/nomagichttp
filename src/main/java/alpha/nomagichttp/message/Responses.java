package alpha.nomagichttp.message;

import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

import static java.net.http.HttpRequest.BodyPublisher;

// TODO: Document
public final class Responses
{
    private Responses() {
        // Empty
    }
    
    public static Response ok() {
        return OK;
    }
    
    public static Response accepted() {
        return ACCEPTED;
    }
    
    public static Response ok(String contentType, BodyPublisher body) {
        return ok(MediaType.parse(contentType), body, body.contentLength());
    }
    
    public static Response ok(MediaType contentType, BodyPublisher body) {
        return ok(contentType, body, body.contentLength());
    }
    
    public static Response ok(String contentType, Flow.Publisher<ByteBuffer> body, long length) {
        return ok(MediaType.parse(contentType), body, length);
    }
    
    public static Response ok(MediaType contentType, Flow.Publisher<ByteBuffer> body, long length) {
        return ResponseBuilder.ok()
                .contentType(contentType)
                .contentLenght(length)
                .body(body);
    }
    
    private static final Response OK = new ResponseBuilder()
            .httpVersion("HTTP/1.1").statusCode(200).reasonPhrase("OK")
            // TODO: Replace with noBody()
            .body(Publishers.empty());
    
    private static final Response ACCEPTED = new ResponseBuilder()
            .httpVersion("HTTP/1.1").statusCode(202).reasonPhrase("Accepted")
            .body(Publishers.empty());
}