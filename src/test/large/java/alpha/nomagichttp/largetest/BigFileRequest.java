package alpha.nomagichttp.largetest;

import alpha.nomagichttp.testutil.AbstractLargeRealTest;
import alpha.nomagichttp.testutil.HttpClientFacade;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.channels.Channel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static alpha.nomagichttp.util.BetterBodyPublishers.ofFile;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE;

/**
 * POST a big file (50 MB) to server, verify disk contents, respond same file.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class BigFileRequest extends AbstractLargeRealTest
{
    private final static int FILE_SIZE = 50 * 1_000_000;
    
    private static Path file;
    private static byte[] contents;
    private static boolean saved;
    
    @BeforeAll
    void beforeAll() throws IOException {
        file = Files.createTempDirectory("nomagic").resolve("big-file");
        contents = DataUtil.bytes(FILE_SIZE);
        server().add("/file",
            GET().respond(ok(ofFile(file))),
            POST().apply(req ->
                req.body().toFile(file, WRITE, CREATE, TRUNCATE_EXISTING)
                          .thenApply(len -> noContent().toBuilder().addHeaders(
                                  "Received", Long.toString(len),
                                  "Connection", "close")
                                .build())));
    }
    
    @Test
    @DisplayName("post/TestClient")
    @Order(1)
    void post() throws IOException {
        final String rsp;
        Channel conn = client().openConnection();
        try (conn) {
            rsp = client()
                      .write(
                          "POST /file HTTP/1.1"          + CRLF +
                          "Content-Length: " + FILE_SIZE + CRLF + CRLF)
                      .write(
                          contents)
                      .shutdownOutput()
                      .readTextUntilEOS();
        }
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 204 No Content" + CRLF +
            "Received: " + FILE_SIZE  + CRLF +
            "Connection: close"       + CRLF + CRLF);
        assertFileContentsOnDisk();
        saved = true;
    }
    
    @Test
    @DisplayName("get/TestClient")
    @Order(2)
    void get() throws IOException {
        assumeTrue(saved);
        final byte[] body;
        Channel conn = client().openConnection();
        try (conn) {
            assertThat(client().writeReadTextUntilNewlines(
                "GET /file HTTP/1.1"                     + CRLF +
                "Connection: close"                      + CRLF + CRLF))
                    .isEqualTo(
                "HTTP/1.1 200 OK"                        + CRLF +
                "Content-Type: application/octet-stream" + CRLF +
                "Content-Length: " + contents.length     + CRLF +
                "Connection: close"                      + CRLF + CRLF);
            body = client().readBytesUntilEOS();
        }
        assertThat(body).isEqualTo(contents);
    }
    
    @ParameterizedTest(name = "post/{0}")
    @EnumSource(mode = EXCLUDE,
            // Reactor do what Reactor does best: NPE.
            names = "REACTOR")
    void post_compatibility(HttpClientFacade.Implementation impl)
            throws IOException, ExecutionException, InterruptedException, TimeoutException
    {
        assumeTrue(saved);
        
        var rsp = impl.create(serverPort())
                .postBytesAndReceiveEmpty("/file", HTTP_1_1, contents);
        
        assertFileContentsOnDisk();
        assertThat(rsp.version()).isEqualTo("HTTP/1.1");
        assertThat(rsp.statusCode()).isEqualTo(204);
        assertThat(rsp.headers().firstValueAsLong("Received")).hasValue(FILE_SIZE);
    }
    
    @ParameterizedTest(name = "get/{0}")
    @EnumSource(mode = EXCLUDE,
            // Jetty has some kind of internal capacity buffer constraint.
            //   java.lang.IllegalArgumentException: Buffering capacity 2097152 exceeded
            // There's a fix for it:
            // https://stackoverflow.com/questions/65941299/buffering-capacity-2097152-exceeded-from-jetty-when-response-is-large
            // ..but not too keen on wasting time tweaking individual clients
            // when all others work.
            names = "JETTY")
    void get_compatibility(HttpClientFacade.Implementation impl)
            throws IOException, ExecutionException, InterruptedException, TimeoutException
    {
        assumeTrue(saved);
        
        var rsp = impl.create(serverPort())
                .getBytes("/file", HTTP_1_1);
        
        assertThat(rsp.version()).isEqualTo("HTTP/1.1");
        assertThat(rsp.statusCode()).isEqualTo(200);
        assertThat(rsp.body()).isEqualTo(contents);
    }
    
    private static void assertFileContentsOnDisk() throws IOException {
        assertThat(Files.readAllBytes(file)).isEqualTo(contents);
    }
}
