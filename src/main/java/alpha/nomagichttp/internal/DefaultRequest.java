package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.route.RouteRegistry;

import java.net.http.HttpHeaders;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.NetworkChannel;
import java.nio.charset.Charset;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static alpha.nomagichttp.util.Headers.contentLength;
import static alpha.nomagichttp.util.Headers.contentType;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Optional.empty;
import static java.util.concurrent.CompletableFuture.failedStage;

final class DefaultRequest implements Request
{
    private static final CompletionStage<Void> COMPLETED = CompletableFuture.completedStage(null);
    
    // Copy-pasted from AsynchronousFileChannel.NO_ATTRIBUTES
    private static final FileAttribute<?>[] NO_ATTRIBUTES = new FileAttribute[0];
    
    private final RequestHead head;
    private final RequestTarget paramsQuery;
    private final RouteRegistry.Match paramsPath;
    private final CompletionStage<Void> bodyStage;
    private final Optional<Body> bodyApi;
    private final OnCancelDiscardOp bodyDiscard;
    private final ChannelOperations child;
    
    DefaultRequest(
            RequestHead head,
            RequestTarget paramsQuery,
            RouteRegistry.Match paramsPath,
            Flow.Publisher<DefaultPooledByteBufferHolder> bodySource,
            ChannelOperations child)
    {
        this.head = head;
        this.paramsQuery = paramsQuery;
        this.paramsPath = paramsPath;
        
        // TODO: If length is not present, then body is possibly chunked.
        // https://tools.ietf.org/html/rfc7230#section-3.3.3
        
        // TODO: Server should throw BadRequestException if Content-Length is present AND Content-Encoding
        // https://tools.ietf.org/html/rfc7230#section-3.3.2
        
        final long len = contentLength(head.headers()).orElse(0);
        
        if (len <= 0) {
            bodyStage   = COMPLETED;
            bodyApi     = empty();
            bodyDiscard = null;
        } else {
            var bounded = new LengthLimitedOp(len, bodySource);
            var observe = new SubscriptionAsStageOp(bounded);
            var onError = new OnErrorCloseReadStream<>(observe, child);
            bodyDiscard = new OnCancelDiscardOp(onError);
            
            bodyStage = observe.asCompletionStage();
            bodyApi = Optional.of(new DefaultBody(headers(), bodyDiscard));
        }
        
        this.child = child;
    }
    
    @Override
    public String method() {
        return head.method();
    }
    
    @Override
    public String target() {
        return head.requestTarget();
    }
    
    @Override
    public String httpVersion() {
        return head.httpVersion();
    }
    
    @Override
    public String toString() {
        return DefaultRequest.class.getSimpleName() + "{head=" + head + ", body=?}";
    }
    
    private Parameters params;
    
    @Override
    public Parameters parameters() {
        Parameters p = params;
        return p != null ? p : (params = new DefaultParameters(paramsPath, paramsQuery));
    }
    
    @Override
    public HttpHeaders headers() {
        return head.headers();
    }
    
    @Override
    public Optional<Body> body() {
        return bodyApi;
    }
    
    @Override
    public NetworkChannel channel() {
        return child.delegate();
    }
    
    @Override
    public boolean channelIsOpenForReading() {
        return child.isOpenForReading();
    }
    
    /**
     * Returns a stage that completes when the body subscription completes.<p>
     * 
     * The returned stage is already completed if the request contains no body.
     * 
     * @return a stage that completes when the body subscription completes
     * 
     * @see SubscriptionAsStageOp
     */
    CompletionStage<Void> bodyStage() {
        return bodyStage;
    }
    
    /**
     * If no downstream body subscriber is active, complete downstream and
     * discard upstream.<p>
     * 
     * Is NOP if body is empty or already discarding.
     */
    void bodyDiscardIfNoSubscriber() {
        if (body().isEmpty()) {
            return;
        }
        
        bodyDiscard.discardIfNoSubscriber();
    }
    
    private static final class DefaultBody implements Request.Body
    {
        private final HttpHeaders headers;
        private final Flow.Publisher<PooledByteBufferHolder> source;
        private final AtomicReference<CompletionStage<String>> cachedText;
        
        DefaultBody(HttpHeaders headers, Flow.Publisher<PooledByteBufferHolder> source) {
            this.headers = headers;
            this.source  = source;
            this.cachedText = new AtomicReference<>(null);
        }
        
        @Override
        public CompletionStage<String> toText() {
            return lazyInit(cachedText, CompletableFuture::new, v -> copyResult(mkText(), v));
        }
        
        private CompletionStage<String> mkText() {
            final Charset charset;
            
            try {
                charset = contentType(headers)
                        .filter(m -> m.type().equals("text"))
                        .map(MediaType::parameters)
                        .map(p -> p.get("charset"))
                        .map(Charset::forName)
                        .orElse(UTF_8);
            } catch (Throwable t) {
                 return failedStage(t);
            }
            
            return convert((buf, count) ->
                    new String(buf, 0, count, charset));
        }
        
        @Override
        public CompletionStage<Long> toFile(Path file, OpenOption... options) {
            return toFile(file, Set.of(options), NO_ATTRIBUTES);
        }
        
        @Override
        public CompletionStage<Long> toFile(Path file, Set<? extends OpenOption> options, FileAttribute<?>... attrs) {
            final Set<? extends OpenOption> opt = !options.isEmpty() ? options :
                    Set.of(WRITE, CREATE, TRUNCATE_EXISTING);
            
            final AsynchronousFileChannel fs;
            
            try {
                // TODO: Potentially re-use server's async group
                //       (currently not possible to specify group?)
                fs = AsynchronousFileChannel.open(file, opt, null, attrs);
            } catch (Throwable t) {
                return failedStage(t);
            }
            
            FileSubscriber s = new FileSubscriber(file, fs);
            subscribe(s);
            return s.asCompletionStage();
        }
        
        @Override
        public <R> CompletionStage<R> convert(BiFunction<byte[], Integer, R> f) {
            HeapSubscriber<R> s = new HeapSubscriber<>(f);
            subscribe(s);
            return s.asCompletionStage();
        }
        
        @Override
        public void subscribe(Flow.Subscriber<? super PooledByteBufferHolder> subscriber) {
            source.subscribe(subscriber);
        }
        
        /**
         * Lazily initialize the value of an atomic reference.<p>
         * 
         * The {@code factory} may be called multiple times when attempted
         * updates fail due to contention among threads. However, the {@code
         * postInit} consumer is only called once by the thread who successfully
         * set the value.<p>
         * 
         * I.e. this method is useful when post-initialization of the value may
         * be expensive but creating the value instance itself is not.
         * 
         * @param ref value container/store
         * @param factory value creator
         * @param postInit value initializer
         * @param <V> value type
         * @param <A> accumulation type
         * 
         * @return the value
         */
        private static <V, A extends V> V lazyInit(
                AtomicReference<V> ref, Supplier<? extends A> factory, Consumer<? super A> postInit)
        {
            class Bag {
                A thing;
            }
            
            Bag created = new Bag();
            
            V latest = ref.updateAndGet(v -> {
                if (v != null) {
                    return v;
                }
                return created.thing = factory.get();
            });
            
            if (latest == created.thing) {
                postInit.accept(created.thing);
            }
            
            return latest;
        }
        
        private static <T> void copyResult(CompletionStage<? extends T> from, CompletableFuture<? super T> to) {
            from.whenComplete((val, exc) -> {
                if (exc != null) {
                    to.completeExceptionally(exc);
                } else {
                    to.complete(val);
                }
            });
        }
    }
    
    private static final class DefaultParameters implements Parameters
    {
        private final RouteRegistry.Match p;
        private final Map<String, List<String>> q, qRaw;
        
        DefaultParameters(RouteRegistry.Match paramsPath, RequestTarget paramsQuery) {
            p = paramsPath;
            q = paramsQuery.queryMapPercentDecoded();
            qRaw = paramsQuery.queryMapNotPercentDecoded();
        }
        
        @Override
        public String path(String name) {
            return p.pathParam(name);
        }
        
        @Override
        public String pathRaw(String name) {
            return p.pathParamRaw(name);
        }
        
        @Override
        public Optional<String> queryFirst(String key) {
            return queryStream(key).findFirst();
        }
        
        @Override
        public Optional<String> queryFirstRaw(String key) {
            return queryStreamRaw(key).findFirst();
        }
        
        @Override
        public Stream<String> queryStream(String key) {
            return queryList(key).stream();
        }
    
        @Override
        public Stream<String> queryStreamRaw(String key) {
            return queryListRaw(key).stream();
        }
        
        @Override
        public List<String> queryList(String key) {
            return queryMap().getOrDefault(key, List.of());
        }
        
        @Override
        public List<String> queryListRaw(String key) {
            return queryMapRaw().getOrDefault(key, List.of());
        }
        
        @Override
        public Map<String, List<String>> queryMap() {
            return q;
        }
    
        @Override
        public Map<String, List<String>> queryMapRaw() {
            return qRaw;
        }
    }
}