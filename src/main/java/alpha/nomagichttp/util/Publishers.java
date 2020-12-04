package alpha.nomagichttp.util;

import alpha.nomagichttp.message.ClosedPublisherException;
import alpha.nomagichttp.message.Response;

import java.net.http.HttpRequest;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.util.Subscriptions.CanOnlyBeCancelled;
import static alpha.nomagichttp.util.Subscriptions.TurnOnProxy;
import static java.util.Objects.requireNonNull;

/**
 * Utility class for constructing thread-safe and non-blocking
 * {@link Flow.Publisher}s.<p>
 * 
 * Publishers produced by this class follows the <a
 * href="https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.3/README.md">
 * Reactive Streams</a> specification to a very large extent. Deviations will be
 * discussed in subsequent sections.<p>
 * 
 * <h3>Thread Semantics</h3>
 * 
 * The Reactive Streams specification requires the publisher to signal the
 * subscriber serially (happens-before relationship between signals). The
 * specification defines a serial signal as being "non-overlapping". At the same
 * time, the specification allow recursion (§3.2, §3.3) which by definition is
 * overlapping otherwise it wouldn't recurse. This class' publishers will signal
 * the subscriber strictly serially with zero overlapping, i.e. no subscriber
 * reentrancy. More concretely; a thread running through {@code
 * Subscriber.onSubscribe()}/{@code onNext()} which synchronously calls {@code
 * Subscription.request()} will always immediately return even if more items are
 * available. Not until the current subscriber call returns will the subscriber
 * receive more items.<p>
 * 
 * The otherwise very async-oriented specification also mandates that the {@code
 * Subscription} object is only called by the same thread executing a method of
 * the related subscriber (§3.1). This severely limits developer freedom;
 * requiring the subscription interaction to occur by a thread of a particular
 * identity at a very narrow and specific time frame. Almost needless to say,
 * the subscription object passed to the subscriber from a publisher created by
 * this class is just like the publisher itself; completely thread-safe and
 * non-blocking. The subscription object may be called by any thread at any time
 * and it will still maintain the happens-before relationship (§1.1).<p>
 * 
 * Truly non-overlapping signals and thread-safety comes with a lot of
 * advantages. For the subscriber developer, there will be no {@code
 * StackOverflowError} gotchas and weird recommendations in the
 * specification "to invoke Subscription methods at the very end of any signal
 * processing" can safely be ignored.<p>
 * 
 * The only thing a subscriber developer should be mindful about is the
 * following: {@code Subscription.cancel()} is only guaranteed to have an
 * immediate effect if called by the thread running the subscriber. If called
 * asynchronously, the effect may be eventual and the subscriber may as a
 * consequence observe an item delivery even after the cancel method has
 * returned (at most one extra delivery).<p>
 * 
 * <h3>Exception Semantics</h3>
 * 
 * Exceptions thrown by {@code Subscriber.onSubscribe()} and {@code onNext()}
 * propagates to the calling thread - after having been forwarded to {@code
 * Subscriber.onError()} as the <i>cause</i> of a {@link
 * ClosedPublisherException}. Having said that, the subscription is voided and
 * the publisher will no longer interact with the subscriber that failed.<p>
 * 
 * Exceptions from {@code Subscriber.onComplete()} will also propagate to the
 * calling thread but is <i>not</i> first sent to {@code onError()} (there's no
 * need; subscription already terminated).<p>
 * 
 * Exceptions from {@code Subscriber.onError()} will be logged but otherwise
 * ignored.<p>
 * 
 * <h3>Other details</h3>
 * 
 * §1.10 and §2.12 requires that a subscriber can not be reused. Not only is
 * this a very weird and unfortunate limitation, effectively putting a stop to
 * subscriber implementation optimizations that rely on reuse - for apparently
 * no good reason, but a naive implementation of the rule on the publisher's
 * side would actually create a memory leak (!). Needless to say, publishers
 * created by this class happily accept the reuse of subscribers.<p>
 * 
 * According to §1.9, the publisher is required to always call {@code
 * Subscriber.onSubscribe()} even if the publisher's intent is to immediately
 * terminate the subscription. It's perhaps misfortune to force a subscriber to
 * initialize even when the publisher has no intention to give the subscriber
 * any items. It can be argued that the rule is counterintuitive which might
 * help explain why the {@code OneShotPublisher} example given in the javadoc of
 * {@link Flow} happened to forget about it. However that might be, this class'
 * publishers - in favor of specification compliance - will always call {@code
 * onSubscribe()} first albeit with a temporary subscription object if the
 * intent is to immediate terminate the subscription. The subscription object
 * will still be monitored and if the subscriber happens to cancel the
 * subscription no more signals will follow (§1.8, §3.12). Requesting demand
 * from the temporary subscription is NOP (see {@link
 * Subscriptions#canOnlyBeCancelled()}).<p>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Response.Builder#body(Flow.Publisher) 
 */
public final class Publishers
{
    private Publishers() {
        // Empty
    }
    
    /**
     * Returns an empty publisher that immediately completes new subscriptions
     * without ever calling {@code Subscriber.onNext()}.<p>
     * 
     * Is an alternative to {@link HttpRequest.BodyPublishers#noBody()} except
     * with less CPU overhead and memory garbage.
     * 
     * @param <T> type of non-existent item (inferred on call site, {@code Void} for example)
     * 
     * @return an empty publisher (global singleton instance)
     */
    public static <T> Flow.Publisher<T> empty() {
        @SuppressWarnings("unchecked")
        Flow.Publisher<T> typed = (Flow.Publisher<T>) Empty.INSTANCE;
        return typed;
    }
    
    /**
     * Creates a publisher that emits a single item.<p>
     * 
     * 
     * The publisher will emit the item immediately upon subscriber subscription
     * and does not limit how many subscriptions at a time can be active. Thus,
     * the item should be thread-safe.<p>
     * 
     * @param item the singleton item
     * @param <T> type of item
     * 
     * @return an empty publisher
     * 
     * @throws NullPointerException if {@code item} is {@code null}
     */
    public static <T> Flow.Publisher<T> singleton(T item) {
        // TODO: Change away from Singleton to ItemPublisher
        return new Singleton<>(item);
    }
    
    /**
     * Creates a {@code Flow.Publisher} that publishes the given {@code items}
     * to each new subscriber.<p>
     * 
     * The publisher will emit the items immediately upon receiving subscriber
     * demand and does not limit how many subscriptions at a time can be active.
     * Thus, either the publisher should be used by only one subscriber at a
     * time or the items should be thread-safe.<p>
     * 
     * According to
     * <a href="https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.3/README.md">
     * Reactive Streams §2.13</a>, a published item must not be {@code null}.
     * This method does not eagerly validate the {@code items} array but if the
     * publisher does come across a {@code null} item while in the process of
     * publishing to a subscriber, the publisher will at that point terminate
     * the subscription and all future subscriptions with a
     * {@link ClosedPublisherException} caused by a
     * {@code NullPointerException}.<p>
     *
     * The given {@code items} may be empty which would return an empty
     * publisher that immediately completes all subscriptions. The difference
     * between an empty publisher from this method and {@link #empty()} is that
     * this method returns a new publisher instance.<p>
     * 
     * @param items to publish
     * @param <T> type of item
     * 
     * @return a new publisher
     * 
     * @throws NullPointerException if {@code items} is {@code null}
     */
    @SafeVarargs
    public static <T> Flow.Publisher<T> just(T... items) {
        return new ItemPublisher<T>(items);
    }
    
    /**
     * Is equivalent to {@link #just(Object[])}.<p>
     * 
     * The order of items published is the same order they are returned by the
     * specified iterable's iterator. The behavior of this operation is
     * undefined if the iterable is modified while the operation is in progress.
     * 
     * @param items to publish
     * @param <T> type of item
     *
     * @return a new publisher
     *
     * @throws NullPointerException if {@code items} is {@code null}
     */
    public static <T> Flow.Publisher<T> just(Iterable<? extends T> items) {
        return new ItemPublisher<T>(items);
    }
    
    private enum Empty implements Flow.Publisher<Void> {
        INSTANCE;
        
        @Override
        public void subscribe(Flow.Subscriber<? super Void> subscriber) {
            CanOnlyBeCancelled tmp = Subscriptions.canOnlyBeCancelled();
            subscriber.onSubscribe(tmp);
            if (!tmp.isCancelled()) {
                subscriber.onComplete();
            }
        }
    }
    
    private static class Singleton<T> implements Flow.Publisher<T> {
        private final T item;
        
        Singleton(T item) {
            this.item = requireNonNull(item);
        }
        
        @Override
        public void subscribe(Flow.Subscriber<? super T> subscriber) {
            subscriber.onSubscribe(new Subscription(subscriber));
        }
        
        private class Subscription implements Flow.Subscription {
            private final Flow.Subscriber<? super T> subscriber;
            private volatile boolean stopped;
            
            Subscription(Flow.Subscriber<? super T> subscriber) {
                this.subscriber = subscriber;
                this.stopped = false;
            }
            
            @Override
            public void request(long n) {
                if (n < 1) {
                    subscriber.onError(new IllegalArgumentException());
                } else if (stopped) {
                    return;
                }
                stopped = true;
                subscriber.onNext(item);
                subscriber.onComplete();
            }
            
            @Override
            public void cancel() {
                stopped = true;
            }
        }
    }
    
    private static final class ItemPublisher<T> implements Flow.Publisher<T>
    {
        private final Iterable<? extends T> items;
        private volatile Throwable exc;
        
        @SafeVarargs
        ItemPublisher(T... items) {
            this(List.of(items));
        }
        
        ItemPublisher(Iterable<? extends T> items) {
            this.items = requireNonNull(items);
            this.exc   = null;
        }
        
        @Override
        public void subscribe(Flow.Subscriber<? super T> s) {
            // TODO: Refactor, make smaller
            requireNonNull(s);
            
            Iterator<? extends T> it = items.iterator();
            
            if (!it.hasNext()) {
                CanOnlyBeCancelled tmp = Subscriptions.canOnlyBeCancelled();
                Subscribers.signalOnSubscribeOrTerminate(s, tmp);
                if (!tmp.isCancelled()) {
                    s.onComplete();
                }
                return;
            }
            
            Throwable e1 = exc;
            if (e1 != null) {
                Subscribers.signalErrorSafe(s, e1);
                return;
            }
            
            SerialTransferService<T> service = new SerialTransferService<>(
                    self -> {
                        if (it.hasNext()) {
                            T t = it.next();
                            if (t == null) {
                                final Throwable e2 = exc = new ClosedPublisherException(new NullPointerException());
                                self.finish(() -> Subscribers.signalErrorSafe(s, e2));
                            }
                            return t;
                        } else {
                            self.finish(s::onComplete);
                            return null;
                        }
                    },
                    s::onNext);
            
            Flow.Subscription subscription = new Flow.Subscription(){
                @Override
                public void request(long n) {
                    try {
                        service.increaseDemand(n);
                    } catch (IllegalArgumentException e3) {
                        service.finish(() -> Subscribers.signalErrorSafe(s, e3));
                    }
                }
                
                @Override
                public void cancel() {
                    service.finish();
                }
            };
            
            TurnOnProxy proxy = Subscriptions.turnOnProxy();
            Subscribers.signalOnSubscribeOrTerminate(s, proxy);
            if (!proxy.isCancelled()) {
                proxy.activate(subscription);
            }
        }
    }
}