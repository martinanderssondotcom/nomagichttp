package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.handler.RequestHandlers;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;

import java.io.IOException;

/**
 * Responds "Hello World!" to client.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class HelloWorld {
    public static void main(String... ignored) throws IOException {
        // In the real world, expressions are often inlined and utility methods statically imported.
        // In the examples, we often choose to be verbose for learning purposes.
        HttpServer app = HttpServer.create();
        
        // All requests coming in to the server expects a Response in return
        // (Response is immutable and so can be cached/shared when possible)
        Response answer = Responses.text("Hello World!");
        
        // Handles requests of the verb/method GET
        RequestHandler handler = RequestHandlers.GET().respond(answer);
        
        // The real contents of the server are resources, addressed by a request path.
        // (just as with Response, the handler too is thread-safe and can be shared)
        app.add("/hello", handler);
        
        // If we don't supply a port number, the system will pick one.
        app.start();
        
        System.out.println("Listening on port " +
                app.getLocalAddress().getPort() + ".");
    }
}