package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.handler.Handlers;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.RouteBuilder;
import alpha.nomagichttp.test.Logging;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.route.Routes.route;
import static java.lang.System.Logger.Level.ALL;
import static org.assertj.core.api.Assertions.assertThat;


/**
 * "Simple" end-to-end server tests, almost like "Hello World!" examples.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class SimpleEndToEndTest extends AbstractEndToEndTest
{
    private static final String CRLF = "\r\n";
    
    @BeforeAll
    static void logEverything() {
        Logging.setLevel(SimpleEndToEndTest.class, ALL);
    }
    
    @Test
    void helloworld() throws IOException, InterruptedException {
        Handler handler = Handlers.GET().supply(() ->
                Responses.ok(TEXT_PLAIN, BodyPublishers.ofString("Hello World!")).asCompletedStage());
        
        Route route = new RouteBuilder("/hello-world")
                .handler(handler).build();
        
        server().getRouteRegistry().add(route);
        
        String req =
            "GET /hello-world HTTP/1.1" + CRLF +
            "Accept: text/plain;charset=utf-8" + CRLF + CRLF;
        
        String resp = writeReadText(req, "World!");
        
        assertThat(resp).isEqualTo(
            "HTTP/1.1 200 OK" + CRLF +
            "Content-Type: text/plain" + CRLF +
            "Content-Length: 12" + CRLF + CRLF +
            
            "Hello World!");
    }
    
    @Test
    void echo_parameter() throws IOException, InterruptedException {
        Handler echo = Handlers.GET().apply(request -> {
            String name = request.paramFromPath("name").get();
            String text = "Hello " + name + "!";
            return Responses.ok(text).asCompletedStage();
        });
        
        Route route = new RouteBuilder("/hello-param").param("name").handler(echo).build();
        server().getRouteRegistry().add(route);
        
        String request =
            "GET /hello-param/John HTTP/1.1" + CRLF +
            "Accept: text/plain;charset=utf-8" + CRLF + CRLF;
        
        String resp = writeReadText(request, "John!");
        
        assertThat(resp).isEqualTo(
            "HTTP/1.1 200 OK" + CRLF +
            "Content-Type: text/plain" + CRLF +
            "Content-Length: 11" + CRLF + CRLF +
            
            "Hello John!");
    }
    
    // TODO: echo query parameter (but this needs to be implemented first lol)
    
    @Test
    void echo_body() throws IOException, InterruptedException {
        Handler echo = Handlers.GET().apply(request -> request.body().toText().thenApply(name -> {
            String greeting = "Hello " + name + "!";
            return Responses.ok(greeting);
        }));
        
        Route route = new RouteBuilder("/hello-body").param("name").handler(echo).build();
        server().getRouteRegistry().add(route);
        
        String request =
            "GET /hello-body HTTP/1.1" + CRLF +
            "Accept: text/plain;charset=utf-8" + CRLF +
            "Content-Length: 4" + CRLF + CRLF +
            
            "John";
        
        String resp = writeReadText(request, "John!");
        
        assertThat(resp).isEqualTo(
            "HTTP/1.1 200 OK" + CRLF +
            "Content-Type: text/plain" + CRLF +
            "Content-Length: 11" + CRLF +
            CRLF +
            "Hello John!");
    }
    
    // TODO: echo body LARGE! Like super large. 100MB or something. Must brake all buffer capacities, that's the point.
    //       Should go to "large" test set.
}
