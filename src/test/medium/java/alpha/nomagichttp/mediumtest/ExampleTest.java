package alpha.nomagichttp.mediumtest;

import alpha.nomagichttp.examples.RetryRequestOnError;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.testutil.HttpClientFacade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.processing;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.HttpClientFacade.Implementation.JDK;
import static alpha.nomagichttp.testutil.HttpClientFacade.Implementation.JETTY;
import static alpha.nomagichttp.testutil.HttpClientFacade.ResponseFacade;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static alpha.nomagichttp.util.Headers.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE;
import static org.junit.jupiter.params.provider.EnumSource.Mode.INCLUDE;

/**
 * Mimics almost all of the examples provided in {@link
 * alpha.nomagichttp.examples}. The only exception is {@link
 * RetryRequestOnError} whose equivalent test is {@link
 * ErrorTest#retryFailedRequest(boolean)}.<p>
 * 
 * The main purpose is to have a guarantee that new code changes won't break
 * code examples.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see DetailTest
 */
class ExampleTest extends AbstractRealTest
{
    @Test
    void HelloWorld() throws IOException {
        addHelloWorldRoute(false);
        
        String req =
            "GET /hello HTTP/1.1"               + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF + CRLF;
        
        String res = client().writeReadTextUntil(req, "World!");
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 12"                      + CRLF + CRLF +
            
            "Hello World!");
    }
    
    @ParameterizedTest
    @EnumSource // <-- in case JUnit didn't know an enum parameter is a..
    void HelloWorld_compatibility(HttpClientFacade.Implementation impl)
            throws IOException, InterruptedException, TimeoutException, ExecutionException
    {
        addHelloWorldRoute(true);
        
        ResponseFacade<String> rsp = impl.create(serverPort())
                .addHeader("Accept", "text/plain; charset=utf-8")
                .getText("/hello", HTTP_1_1);
        
        assertThat(rsp.version()).isEqualTo("HTTP/1.1");
        assertThat(rsp.statusCode()).isEqualTo(200);
        if (impl != JDK) {
            // Assume all other supports retrieving the reason-phrase
            assertThat(rsp.reasonPhrase()).isEqualTo("OK");
        }
        assertThat(rsp.headers()).isEqualTo(of(
            "Content-Type",   "text/plain; charset=" +
                // Lowercase version is what the server sends.
                (impl == JETTY ? "UTF-8" : "utf-8"),
            "Content-Length", "12",
            "Connection",     "close"));
        assertThat(rsp.body()).isEqualTo(
            "Hello World!");
    }
    
    private void addHelloWorldRoute(boolean closeChild) throws IOException {
        var rsp = tryScheduleClose(text("Hello World!"), closeChild);
        server().add("/hello", GET().respond(rsp));
    }
    
    @Test
    void GreetParameter() throws IOException {
        addGreetParameterRoutes(false);
        
        String req1 =
            "GET /hello/John HTTP/1.1"          + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF + CRLF;
        
        String req2 =
            "GET /hello?name=John HTTP/1.1"     + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF + CRLF;
        
        String res1 = client().writeReadTextUntil(req1, "John!");
        assertThat(res1).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 11"                      + CRLF + CRLF +
            
            "Hello John!");
        
        String res2 = client().writeReadTextUntil(req2, "John!");
        assertThat(res2).isEqualTo(res1);
    }
    
    @ParameterizedTest
    @EnumSource
    void GreetParameter_compatibility(HttpClientFacade.Implementation impl)
            throws IOException, ExecutionException, InterruptedException, TimeoutException
    {
        addGreetParameterRoutes(true);
        
        HttpClientFacade req = impl.create(serverPort())
                .addHeader("Accept", "text/plain; charset=utf-8");
        
        var rsp1 = req.getText("/hello/John", HTTP_1_1);
        
        assertThat(rsp1.version()).isEqualTo("HTTP/1.1");
        assertThat(rsp1.statusCode()).isEqualTo(200);
        if (impl != JDK) {
            // Assume all other supports retrieving the reason-phrase
            assertThat(rsp1.reasonPhrase()).isEqualTo("OK");
        }
        assertThat(rsp1.headers()).isEqualTo(of(
            "Content-Type",   "text/plain; charset=" +
                (impl == JETTY ? "UTF-8" : "utf-8"),
            "Content-Length", "11",
            "Connection",     "close"));
        assertThat(rsp1.body()).isEqualTo(
            "Hello John!");
        
        // TODO: a path/query parameter API in the facade that uses the
        //       implementation's API for building the encoded URI
        var rsp2 = req.getText("/hello?name=John", HTTP_1_1);
        assertThat(rsp1).isEqualTo(rsp2);
    }
    
    private void addGreetParameterRoutes(boolean closeChild) throws IOException {
        Function<String, CompletionStage<Response>> factory = name -> {
            var rsp = tryScheduleClose(text("Hello " + name + "!"), closeChild);
            return rsp.completedStage();
        };
        
        server().add("/hello/:name", GET().apply(req -> {
            String n = req.parameters().path("name");
            return factory.apply(n);
        }));
        
        server().add("/hello", GET().apply(req -> {
            String n = req.parameters().queryFirst("name").get();
            return factory.apply(n);
        }));
    }
    
    @Test
    void GreetBody() throws IOException {
        addGreetBodyRoute(false);
        
        String req =
            "POST /hello HTTP/1.1"                    + CRLF +
            "Accept: text/plain; charset=utf-8"       + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 4"                       + CRLF + CRLF +
            
            "John";
        
        String res = client().writeReadTextUntil(req, "John!");
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 11"                      + CRLF + CRLF +
            
            "Hello John!");
    }
    
    @ParameterizedTest
    @EnumSource
    void GreetBody_compatibility(HttpClientFacade.Implementation impl)
            throws IOException, InterruptedException, ExecutionException, TimeoutException
    {
        addGreetBodyRoute(true);
        
        HttpClientFacade req = impl.create(serverPort())
                .addHeader("Accept", "text/plain; charset=utf-8");
        
        ResponseFacade<String> rsp = req.postAndReceiveText(
                "/hello", HTTP_1_1, "John");
        
        assertThat(rsp.version()).isEqualTo("HTTP/1.1");
        assertThat(rsp.statusCode()).isEqualTo(200);
        if (impl != JDK) {
            assertThat(rsp.reasonPhrase()).isEqualTo("OK");
        }
        assertThat(rsp.headers()).isEqualTo(of(
            "Content-Type",   "text/plain; charset=" +
                (impl == JETTY ? "UTF-8" : "utf-8"),
            "Content-Length", "11",
            "Connection",     "close"));
        assertThat(rsp.body()).isEqualTo(
            "Hello John!");
    }
    
    private void addGreetBodyRoute(boolean closeChild) throws IOException {
        RequestHandler echo = POST().apply(req ->
                req.body().toText().thenApply(name ->
                        tryScheduleClose(text("Hello " + name + "!"), closeChild)));
        
        server().add("/hello", echo);
    }
    
    @Test
    void EchoHeaders() throws IOException {
        addEchoHeadersRoute(false);
        
        String req =
            "GET /echo HTTP/1.1"        + CRLF +
            "My-Header: Value 1"        + CRLF +
            "My-Header: Value 2"        + CRLF + CRLF;
        
        String res = client().writeReadTextUntilNewlines(req);
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 204 No Content"   + CRLF +
            "My-Header: Value 1"        + CRLF + 
            "My-Header: Value 2"        + CRLF + CRLF);
    }
    
    @ParameterizedTest
    // OkHttp will drop "Value 1" and only report "Value 2".
    // Am I surprised? No. Do I care? No.
    // Is it excluded from the test so that my life can go on? Yes.
    @EnumSource(mode = EXCLUDE, names = "OKHTTP")
    void EchoHeaders_compatibility(HttpClientFacade.Implementation impl)
            throws IOException, ExecutionException, InterruptedException, TimeoutException
    {
        addEchoHeadersRoute(true);
        
        var rsp = impl.create(serverPort())
                .addHeader("My-Header", "Value 1")
                .addHeader("My-Header", "Value 2")
                .getEmpty("/echo", HTTP_1_1);
        
        assertThat(rsp.version()).isEqualTo("HTTP/1.1");
        assertThat(rsp.statusCode()).isEqualTo(204);
        if (impl != JDK) {
            assertThat(rsp.reasonPhrase()).isEqualTo("No Content");
        }
        
        // The clients send - and thus receive - different headers.
        // Most obviously the value of the "Host: " header.
        // What we care about here is getting our custom headers back.
        assertThat(rsp.headers().allValues(
            "My-Header")).containsExactly("Value 1", "Value 2");
    }
    
    private void addEchoHeadersRoute(boolean closeChild) throws IOException {
        Function<Request, Response> rsp = req -> Responses.noContent()
                .toBuilder()
                .addHeaders(req.headers())
                .build();
        
        server().add("/echo", GET().apply(req ->
                tryScheduleClose(rsp.apply(req), closeChild)
                        .completedStage()));
    }
    
    @Test
    public void KeepClientInformed() throws IOException {
        addKeepClientInformedRoute(false);
        
        String rsp = client().writeReadTextUntil(
            "GET / HTTP/1.1" + CRLF + CRLF, "Done!");
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 102 Processing"                 + CRLF + CRLF +
            
            "HTTP/1.1 102 Processing"                 + CRLF + CRLF +
            
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 5"                       + CRLF + CRLF +
            
            "Done!");
    }
    
    @ParameterizedTest
    // Only Apache (and curl!) will pass this test lol.
    // JDK takes everything after the first 102 (Processing) as the response body.
    // OkHttp and Jetty yields an empty body ("").
    // Reactor does what Reactor does best; crashes with a NullPointerException.
    @EnumSource(mode = INCLUDE, names = "APACHE")
    public void KeepClientInformed_compatibility(HttpClientFacade.Implementation impl)
            throws IOException, ExecutionException, InterruptedException, TimeoutException
    {
        addKeepClientInformedRoute(true);
        var rsp = impl.create(serverPort()).getText("/", HTTP_1_1);
        assertThat(rsp.body()).isEqualTo("Done!");
    }
    
    private void addKeepClientInformedRoute(boolean closeChild) throws IOException {
        server().add("/", GET().accept((req, ch) -> {
            ch.write(processing());
            ch.write(processing());
            ch.write(tryScheduleClose(text("Done!"), closeChild));
        }));
    }
    
    // TODO: Currently not a "public" example in the docs.
    //       Will wait until after we have done improved file serving.
    //       The compatibility test ought to use a client-native API for uploading a file.
    @Test
    void UploadFile() throws IOException, InterruptedException {
        // 1. Save to new file
        // ---
        Path file = Files.createTempDirectory("nomagic")
                .resolve("some-file.txt");
        
        RequestHandler saver = POST().apply(req ->
                req.body().toFile(file)
                          .thenApply(n -> Long.toString(n))
                          .thenApply(Responses::text));
        
        server().add("/small-file", saver);
        
        final String reqHead =
            "POST /small-file HTTP/1.1" + CRLF +
            "Content-Length: 3"         + CRLF + CRLF;
        
        String res1 = client().writeReadTextUntil(reqHead + "Foo", "3");
        
        assertThat(res1).isEqualTo(
            "HTTP/1.1 200 OK"                          + CRLF +
            "Content-Type: text/plain; charset=utf-8"  + CRLF +
            "Content-Length: 1"                        + CRLF + CRLF +
            
            "3");
        assertThat(Files.readString(file)).isEqualTo("Foo");
        
        // 2. By default, existing files are not overwritten
        // ---
        String res2 = client().writeReadTextUntilNewlines(reqHead + "Bar");
        
        assertThat(res2).isEqualTo(
            "HTTP/1.1 500 Internal Server Error" + CRLF +
            "Content-Length: 0"                  + CRLF + CRLF);
        assertThat(pollServerError()).isExactlyInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(file)).isEqualTo("Foo");
    }
    
    private static Response tryScheduleClose(Response rsp, boolean close) {
        if (!close) {
            return rsp;
        }
        return rsp.toBuilder().mustCloseAfterWrite(true).build();
    }
}
