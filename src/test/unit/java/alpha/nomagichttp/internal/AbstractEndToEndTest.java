package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.testutil.ClientOperations;
import alpha.nomagichttp.testutil.Logging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

import static java.lang.System.Logger.Level.ALL;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Will setup a {@code server()} and a {@code client()} configured with the
 * server's port; scoped to each test.<p>
 * 
 * The server has no routes added, but, has an error handler added which simply
 * collects all exceptions caught into a {@code BlockingDeque} and then
 * delegates the error handling to the default error handler.<p>
 * 
 * By default, after-each will assert that no errors were delivered to the error
 * handler. If errors are expected, then the test must consume all errors from
 * the deque returned from {@code errors()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see SimpleEndToEndTest
 * @see DetailedEndToEndTest
 */
abstract class AbstractEndToEndTest
{
    private static HttpServer server;
    private static ClientOperations client;
    
    private final BlockingDeque<Throwable> errors = new LinkedBlockingDeque<>();
    
    @BeforeEach
    void start() throws IOException {
        Logging.setLevel(ALL);
        
        ErrorHandler collect = (t, r, h) -> {
            errors.add(t);
            throw t;
        };
        
        server = HttpServer.create(collect).start();
        client = new ClientOperations(server);
    }
    
    @AfterEach
    void assertNoErrors() {
        assertThat(errors).isEmpty();
    }
    
    @AfterEach
    void stop() throws IOException {
        if (server != null) {
            server.stop();
        }
    }
    
    public static HttpServer server() {
        return server;
    }
    
    public static ClientOperations client() {
        return client;
    }
    
    public final BlockingDeque<Throwable> errors() {
        return errors;
    }
    
    /**
     * Same as {@code errors().poll(3, SECONDS)}.
     * 
     * @return same as {@code errors().poll(3, SECONDS)}
     */
    public final Throwable pollError() throws InterruptedException {
        return errors().poll(3, SECONDS);
    }
}