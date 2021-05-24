package alpha.nomagichttp.real;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.testutil.Logging;
import alpha.nomagichttp.testutil.TestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.lang.System.Logger.Level.ALL;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Will setup a {@link #server()} and a {@link #client()}, the latter configured
 * with the server's port. Both scoped to each test.<p>
 * 
 * The server has no routes added and so most test cases will probably have to
 * add those in manually.<p>
 * 
 * This class registers en error handler which collects all server exceptions
 * into a {@code BlockingDeque} and then delegates the error handling to the
 * default error handler.<p>
 * 
 * By default, after-each will assert that no errors were delivered to the error
 * handler. If errors are expected, then the test must consume all errors from
 * the deque using {@link #pollServerError()}.<p>
 * 
 * Log recording will be activated before starting the server. The recorder can
 * be retrieved using {@link #logRecorder()}. Records can be retrieved at any
 * time using {@link #stopLogRecording()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see ExampleTest
 * @see DetailTest
 */
abstract class AbstractRealTest
{
    final Logger LOG =  Logger.getLogger(getClass().getPackageName());
    
    private Logging.Recorder key;
    private HttpServer server;
    private int port;
    private TestClient client;
    private final BlockingDeque<Throwable> errors = new LinkedBlockingDeque<>();
    
    @BeforeEach
    void __start(TestInfo test) throws IOException {
        Logging.setLevel(ALL);
        LOG.log(INFO, "Executing " + toString(test));
        key = Logging.startRecording();
        
        ErrorHandler collect = (t, r, c, h) -> {
            errors.add(t);
            throw t;
        };
        
        server = HttpServer.create(collect).start();
        port   = server.getLocalAddress().getPort();
        client = new TestClient(server);
    }
    
    @AfterEach
    void __stopNow(TestInfo test) throws IOException {
        server.stopNow();
        stopLogRecording();
        LOG.log(INFO, "Finished " + toString(test));
    }
    
    @AfterEach
    void __assertNoErrors() {
        assertThat(errors).isEmpty();
    }
    
    /**
     * Returns the server instance.
     * 
     * @return the server instance
     */
    protected final HttpServer server() {
        return server;
    }
    
    /**
     * Returns the cached server port.<p>
     * 
     * This method is useful for testing communication on the port even after
     * the server has stopped (at which point the port can no longer be
     * retrieved from the server).
     * 
     * @return the cached server port
     */
    protected final int serverPort() {
        return port;
    }
    
    /**
     * Returns the client instance.
     *
     * @return the client instance
     */
    protected final TestClient client() {
        return client;
    }
    
    /**
     * Poll an error caught by the error handler, waiting at most 3 seconds.
     * 
     * @return an error, or {@code null} if none is available
     * 
     * @throws InterruptedException if interrupted while waiting
     */
    protected final Throwable pollServerError() throws InterruptedException {
        return errors.poll(3, SECONDS);
    }
    
    /**
     * Returns the test log recorder.
     * 
     * @return the test log recorder
     */
    protected final Logging.Recorder logRecorder() {
        return key;
    }
    
    /**
     * Stop log recording.
     * 
     * @return all logged records
     */
    protected final Stream<LogRecord> stopLogRecording() {
        return Logging.stopRecording(key);
    }
    
    /**
     * Waits for at most 3 seconds on the server log to indicate a child was
     * accepted.
     * 
     * @throws InterruptedException
     *             if the current thread is interrupted while waiting
     */
    protected final void awaitChildAccept() throws InterruptedException {
        assertTrue(logRecorder().await(FINE, "Accepted child:"));
    }
    
    /**
     * Waits for at most 3 seconds on the server log to indicate a child was
     * closed.
     * 
     * @throws InterruptedException
     *             if the current thread is interrupted while waiting
     */
    protected final void awaitChildClose() throws InterruptedException {
        assertTrue(logRecorder().await(FINE, "Closed child:"));
    }
    
    private static String toString(TestInfo test) {
        Method m = test.getTestMethod().get();
        return m.getDeclaringClass().getSimpleName() + "." + m.getName() + "()";
    }
}