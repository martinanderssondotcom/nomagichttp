package alpha.nomagichttp.real;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.testutil.Logging;
import alpha.nomagichttp.testutil.TestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static alpha.nomagichttp.Config.configuration;
import static alpha.nomagichttp.HttpServer.create;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.HEAD;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.handler.RequestHandler.TRACE;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.message.Responses.processing;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static alpha.nomagichttp.testutil.TestConfig.timeoutIdleConnection;
import static alpha.nomagichttp.testutil.TestPublishers.blockSubscriber;
import static alpha.nomagichttp.testutil.TestSubscribers.onError;
import static alpha.nomagichttp.util.BetterBodyPublishers.concat;
import static alpha.nomagichttp.util.BetterBodyPublishers.ofString;
import static java.lang.System.Logger.Level.ALL;
import static java.time.Duration.ofMillis;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests concerning server errors.<p>
 * 
 * In particular, tests here usually install custom error handlers and/or run
 * asserts on errors delivered to the error handler.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Extend AbstractRealTest
class ErrorTest
{
    HttpServer s;
    
    @BeforeAll
    static void setLogging() {
        Logging.setLevel(ALL);
    }
    
    @AfterEach
    void stopServer() throws IOException {
        if (s != null) {
            s.stopNow();
        }
    }
    
    @Test
    void not_found_default() throws IOException {
        s = create().start();
        String r = new TestClient(s).writeRead(
            "GET /404 HTTP/1.1" + CRLF + CRLF);
        
        assertThat(r).isEqualTo(
            "HTTP/1.1 404 Not Found" + CRLF +
            "Content-Length: 0"      + CRLF + CRLF);
    }
    
    @Test
    void not_found_custom() throws IOException {
        ErrorHandler eh = (exc, ch, req, han) -> {
            if (exc instanceof NoRouteFoundException) {
                ch.write(Response.builder(499, "Custom Not Found!").build());
                return;
            }
            throw exc;
        };
        
        s = create(eh).start();
        String res = new TestClient(s).writeRead(
            "GET /404 HTTP/1.1" + CRLF + CRLF);
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 499 Custom Not Found!" + CRLF + CRLF);
    }
    
    @Test
    void request_too_large() throws IOException {
        s = create(configuration().maxRequestHeadSize(1).build())
                .add("/", GET().accept((req, ch) -> {
                    throw new AssertionError(); }))
                .start();
        
        String rsp = new TestClient(s).writeRead("AB");
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 413 Entity Too Large" + CRLF +
            "Connection: close"             + CRLF + CRLF);
        
        // TODO: Error handler received MaxRequestHeadSizeExceededException, and assert log
    }
    
    /** Request handler fails synchronously. */
    @Test
    void retry_failed_request_sync() throws IOException {
        firstTwoRequestsResponds(() -> { throw new RuntimeException(); });
    }
    
    /** Returned stage completes exceptionally. */
    @Test
    void retry_failed_request_async() throws IOException {
        firstTwoRequestsResponds(() -> failedFuture(new RuntimeException()));
    }
    
    private void firstTwoRequestsResponds(Supplier<CompletionStage<Response>> response)
            throws IOException
    {
        AtomicInteger c = new AtomicInteger();
        
        RequestHandler h1 = GET().respond(() -> {
            if (c.incrementAndGet() < 3) {
                return response.get();
            }
            
            return noContent().toBuilder()
                              .header("N", Integer.toString(c.get()))
                              .build()
                              .completedStage();
        });
        
        ErrorHandler retry = (t, ch, r, h2) -> h2.logic().accept(r, ch);
        
        s = create(retry).add("/", h1).start();;
        String r = new TestClient(s).writeRead(
            "GET / HTTP/1.1" + CRLF + CRLF);
        
        assertThat(r).isEqualTo(
            "HTTP/1.1 204 No Content" + CRLF +
            "N: 3"                    + CRLF + CRLF);
    }
    
    @Test
    void httpVersionBad() throws IOException {
        s = create().start();;
        String res = new TestClient(s).writeRead(
            "GET / Ooops" + CRLF + CRLF);
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 400 Bad Request" + CRLF +
            "Content-Length: 0"        + CRLF + CRLF);
    }
    
    /**
     * By default, server rejects clients older than HTTP/1.0.
     * 
     * @throws IOException if an I/O error occurs
     */
    @Test
    void httpVersionRejected_tooOld_byDefault() throws IOException {
        s = create().start();;
        TestClient c = new TestClient(s);
        
        for (String v : List.of("-1.23", "0.5", "0.8", "0.9")) {
             String res = c.writeRead(
                 "GET / HTTP/" + v + CRLF + CRLF);
             
             assertThat(res).isEqualTo(
                 "HTTP/1.1 426 Upgrade Required" + CRLF +
                 "Upgrade: HTTP/1.1"             + CRLF +
                 "Connection: Upgrade"           + CRLF +
                 "Content-Length: 0"             + CRLF + CRLF);
        }
    }
    
    /**
     * Server may be configured to reject HTTP/1.0 clients.
     * 
     * @throws IOException if an I/O error occurs
     */
    @Test
    void httpVersionRejected_tooOld_thruConfig() throws IOException {
        var cfg = configuration().rejectClientsUsingHTTP1_0(true).build();
        s = create(cfg).start();
        String r = new TestClient(s).writeRead(
            "GET /not-found HTTP/1.0" + CRLF + CRLF);
        
        assertThat(r).isEqualTo(
            "HTTP/1.0 426 Upgrade Required" + CRLF +
            "Upgrade: HTTP/1.1"             + CRLF +
            "Connection: close"             + CRLF +
            "Content-Length: 0"             + CRLF + CRLF);
    }
    
    @Test
    void httpVersionRejected_tooNew() throws IOException {
        s = create().start();;
        TestClient c = new TestClient(s);
        
        for (String v : List.of("2", "3", "999")) {
             String r = c.writeRead(
                 "GET / HTTP/" + v + CRLF + CRLF);
             
             assertThat(r).isEqualTo(
                 "HTTP/1.1 505 HTTP Version Not Supported" + CRLF +
                 "Content-Length: 0"                       + CRLF +
                 "Connection: close"                       + CRLF + CRLF);
        }
    }
    
    // TODO: When this class extends AbstractRealTest, assert default handler caught
    //       IllegalBodyException: Body in response to a HEAD request.
    @Test
    void IllegalBodyException_inResponseToHEAD() throws IOException {
        s = create().start();
        s.add("/", HEAD().respond(text("Body!")));
        
        String res = new TestClient(s)
                .writeRead("HEAD / HTTP/1.1" + CRLF + CRLF);
        
        assertThat(res).isEqualTo(
                "HTTP/1.1 500 Internal Server Error" + CRLF +
                "Content-Length: 0"                  + CRLF + CRLF);
    }
    
    // TODO: Same here as last test, assert log
    @Test
    void IllegalBodyException_inRequestFromTRACE() throws IOException {
        s = create().start();
        s.add("/", TRACE().accept((req, ch) -> {
            throw new AssertionError("Not invoked.");
        }));
        
        String res = new TestClient(s).writeRead(
                "TRACE / HTTP/1.1"  + CRLF +
                "Content-Length: 1" + CRLF + CRLF +
                
                "X");
        
        assertThat(res).isEqualTo(
                "HTTP/1.1 400 Bad Request" + CRLF +
                "Content-Length: 0"        + CRLF + CRLF);
    }
    
    @Test
    void IllegalBodyException_in1xxResponse() throws IOException {
        s = create().start();
        s.add("/", GET().respond(() ->
                Response.builder(123)
                        .body(ofString("Body!"))
                        .build()
                        .completedStage()));
        
        String res = new TestClient(s)
                .writeRead("GET / HTTP/1.1" + CRLF + CRLF);
        
        assertThat(res).isEqualTo(
                "HTTP/1.1 500 Internal Server Error" + CRLF +
                "Content-Length: 0"                  + CRLF + CRLF);
    }
    
    @Test
    void ResponseRejectedException_interimIgnoredForOldClient() throws IOException {
        s = create().start();
        
        s.add("/", GET().accept((req, ch) -> {
            ch.write(processing()); // <-- ignored...
            ch.write(text("Done!"));
        }));
        
        // ... because "HTTP/1.0"
        String res = new TestClient(s)
                .writeRead("GET / HTTP/1.0" + CRLF + CRLF, "Done!");
        
        assertThat(res).isEqualTo(
            "HTTP/1.0 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 5"                       + CRLF +
            "Connection: close"                       + CRLF + CRLF +
            
            "Done!");
    }
    
    @Test
    void RequestHeadTimeoutException() throws IOException {
        // Return uber low timeout on the first poll, i.e. for the request head,
        // but use default timeout for request body and response.
        Config lowHeadTimeout = timeoutIdleConnection(1, ofMillis(0));
        s = create(lowHeadTimeout).start();
        
        String res = new TestClient(s)
                // Server waits for CRLF + CRLF, but times out instead
                .writeRead("GET / HTTP...");
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 408 Request Timeout" + CRLF +
            "Content-Length: 0"            + CRLF +
            "Connection: close"            + CRLF + CRLF);
        
        // TODO: When we extend AbstractRealTest
        //         1) poll and assert RequestHeadTimeoutException
        //         2) assert log
    }
    
    @Test
    void RequestBodyTimeoutException_caughtByServer() throws IOException, InterruptedException {
        final BlockingQueue<Throwable> appErr = new ArrayBlockingQueue<>(1);
        s = create(timeoutIdleConnection(2, ofMillis(0)))
                // The async timeout, even though instant in this case, does
                // not abort the eminent request handler invocation.
                .add("/", POST().accept((req, ch) -> {
                    // TODO: When extending AbstractRealTest,
                    //       poll and await RequestBodyTimeoutException instead of this
                    try {
                        // This suffer from the same "blocked thread" problem
                        // other not-written test cases related to timeouts have.
                        // Need to figure out a better way.
                        MILLISECONDS.sleep(100);
                    } catch (InterruptedException e) {
                        appErr.add(e);
                        return;
                    }
                    // or body().toText().exceptionally(), doesn't matter
                    req.body().subscribe(onError(appErr::add));
                }))
                .start();
        
        String res = new TestClient(s).writeRead(
                "POST / HTTP/1.1"   + CRLF +
                "Content-Length: 2" + CRLF + CRLF +
                
                "1");
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 408 Request Timeout" + CRLF +
            "Content-Length: 0"            + CRLF +
            "Connection: close"            + CRLF + CRLF);
        
        assertThat(appErr.poll(3, SECONDS))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Publisher was already subscribed to and is not reusable.");
        
        // TODO: When we extend AbstractRealTest, assert log
    }
    
    // RequestBodyTimeoutException_caughtByApp() ??
    // Super tricky to do deterministically without also blocking the test. Skipping for now.
    
    // Low-level write timeout by InterruptedByTimeoutException?
    // Same. Can't do deterministically. Need to mock the channel.
    
    @Test
    void ResponseTimeoutException_fromPipeline() throws IOException {
        s = create(timeoutIdleConnection(3, ofMillis(0)))
                .add("/", GET().accept((ign,ored) -> {}))
                .start();
        
        String res = new TestClient(s).writeRead(
            "GET / HTTP/1.1"                   + CRLF + CRLF);
        assertThat(res).isEqualTo(
            "HTTP/1.1 503 Service Unavailable" + CRLF +
            "Content-Length: 0"                + CRLF +
            "Connection: close"                + CRLF + CRLF);
        
        // TODO: When we extend AbstractRealTest
        //         1) poll and assert ResponseTimeoutException: "Gave up waiting on a response."
        //         2) assert log
    }
    
    @Test
    void ResponseTimeoutException_fromResponseBody_immediately() throws IOException {
        s = create(timeoutIdleConnection(4, ofMillis(0)))
                .add("/", GET().accept((req, ch) ->
                    ch.write(ok(blockSubscriber()))))
                .start();
    
        // Response may be empty, may be 503 (Service Unavailable).
        // What this test currently is that the client get's a response or connection closes.
        // (otherwise our client would have timed out on this side)
        String responseIgnored = new TestClient(s).writeRead(
                "GET / HTTP/1.1" + CRLF + CRLF);
        
        // TODO: Need to figure out how to release the permit on timeout and then assert log
    }
    
    // ResponseTimeoutException_fromResponseBody_afterOneChar?
    // No way to do deterministically, at least without tapping into the production code.
    
    @Test
    void ResponseTimeoutException_fromResponseBody_afterOneChar() throws IOException {
        s = create(timeoutIdleConnection(4, ofMillis(0)))
                .add("/", GET().accept((req, ch) ->
                    ch.write(ok(concat(ofString("X"), blockSubscriber())))))
                .start();
        
        String responseIgnored = new TestClient(s).writeRead(
                "GET / HTTP/1.1" + CRLF + CRLF, "until server close plz");
        
        // <res> may/may not contain none, parts, or all of the response
        
        // TODO: Same here, release permit and assert log.
        //       We should then also be able to assert the start of the 200 OK response?
    }
}