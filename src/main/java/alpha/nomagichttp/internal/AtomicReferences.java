package alpha.nomagichttp.internal;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Utilities for {@link AtomicReference}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class AtomicReferences
{
    private AtomicReferences() {
        // Empty
    }
    
    /**
     * Lazily initialize a new value of an atomic reference.<p>
     * 
     * The {@code factory} may be called multiple times when attempted
     * updates fail due to contention among threads. However, the {@code
     * postInit} consumer is only called exactly once by the thread who
     * successfully updated the reference value.<p>
     * 
     * I.e. this method is useful when constructing the value instance is not
     * expensive but post-initialization of the object is.<p>
     * 
     * Note:
     * <ol>
     *   <li>A non-null value is never replaced. If the reference is already
     *       set, neither factory nor initializer is called - the current value
     *       is returned.</li>
     *   <li>Atomic reference is set first, then post initialization runs. I.e.
     *       any thread consuming the reference value may at any time observe
     *       the semantically uninitialized value, even whilst the
     *       initialization operation is running or even if the operation
     *       returned exceptionally.</li>
     *   <li>Therefore, make sure the accumulation type can intrinsically
     *       orchestrate its post-initialized state to multiple threads without
     *       data corruption- or race. This method was designed with a {@code
     *       CompletableFuture} accumulator in mind. E.g., many threads will
     *       observe the same result carrier, but only one of them proceeds to
     *       compute its result.</li>
     * </ol>
     * 
     * @param ref value container/store
     * @param factory value creator
     * @param postInit value initializer
     * @param <V> value type
     * @param <A> accumulation type
     * 
     * @return the value
     */
    static <V, A extends V> V lazyInit(
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
    
    /**
     * Lazily initialize a new value of an atomic reference, or else return an
     * alternative value.<p>
     * 
     * This method behaves the same as {@link
     * #lazyInit(AtomicReference, Supplier, Consumer)}, except only the
     * initializing thread will also observe the reference value, all others
     * will get the alternative. This effectively changes the atomic reference
     * function from being a non-discriminatory value container to being
     * reserved only for the initializer.<p>
     * 
     * Think of it as a sort of a concurrency primitive akin to a non-blocking
     * one permit {@link Semaphore Semaphore}{@code .tryAcquire()} where only
     * the initializer succeeds by receiving the permit (a typed value in this
     * case) and the primitive can then be reset (permit released) by setting
     * the reference back to null.
     * 
     * @param ref value container/store
     * @param factory value creator
     * @param postInit value initializer
     * @param alternativeValue to give any thread not also being the initializer
     * @param <V> value type
     * @param <A> accumulation type
     *
     * @return the value
     */
    static <V, A extends V> V lazyInitOrElse(
            AtomicReference<V> ref, Supplier<? extends A> factory, Consumer<? super A> postInit, V alternativeValue)
    {
        // Copy-pasted from lazyInit(). Implementing DRY through a common method
        // would probably be hard to understand?
        
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
            return latest; // <-- this is one of two changed lines
        }
        
        return alternativeValue; // <-- this also changed
    }
    
    /**
     * Atomically set the value of the reference to the factory-produced value
     * only if the actual value is {@code null}.
     * 
     * @param ref value container
     * @param newValue to set if actual value is {@code null}
     * @param <V> value type
     * 
     * @return actual value if not {@code null}, otherwise {@code newValue}
     */
    static <V> V setIfAbsent(AtomicReference<V> ref, V newValue) {
        return ref.updateAndGet(act -> {
            if (act != null) {
                return act;
            }
            return newValue;
        });
    }
}