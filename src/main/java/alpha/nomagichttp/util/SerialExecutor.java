package alpha.nomagichttp.util;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import static java.lang.ThreadLocal.withInitial;

/**
 * Executes actions serially in FIFO order without overlapping unless configured
 * to allow for recursion.<p>
 * 
 * Actions are executed using the same thread calling, or if the executor is
 * already busy running a previously added action, possibly schedules the action
 * to be executed in the future, either by the active thread currently operating
 * the executor or another inbound thread - whichever thread wins a race to
 * start at that time.<p>
 * 
 * If the executor is busy and a new action arrives, whether or not the action
 * executes directly or is scheduled depends on thread identity and a boolean
 * {@code mayRecurse} constructor argument.<p>
 * 
 * If {@code mayRecurse} is {@code false} then all actions will be scheduled for
 * the future no matter which thread is calling in; this stops recursion from
 * happening. It's a safe strategy as it will 1) never produce a {@code
 * StackOverflowError}, 2) be more fair in execution order (no action will take
 * precedence over another) and 3) at times of contention; has a higher
 * probability of distributing work amongst threads.<p>
 * 
 * Alas, there's one noticeable drawback with using {@code mayRecurse} {@code
 * false}. Without recursion, execution may appear to be re-ordered. For
 * example, suppose the logic of function A calls function B and both run
 * through the same SerialExecutor, then A will complete first before B
 * executes. With recursion, B would have executed before A completes. Whether
 * or not this has an impact on program correctness is very much dependent on
 * the context of the use site.<p>
 * 
 * With {@code mayRecurse} {@code true}, a thread already executing an action
 * will recursively execute new actions posted from the same thread without
 * regards to how many other actions is sitting in the queue. This may be vital
 * for program correctness and also comes with a performance improvement since
 * recursive actions impose virtually no overhead at all.<p>
 * 
 * Actions are executed with full memory synchronization in-between. I.e., the
 * actions may safely read and write common non-volatile fields. Further,
 * anything done by a thread prior to enqueuing an action happens-before the
 * execution of that action.<p>
 * 
 * The first action's earliest synchronization-with point is the constructor of
 * this class; so, any state accessed by the first action must be safely
 * published. For example, initialize all state fields first, then create the
 * {@code SerialExecutor} instance afterwards.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class SerialExecutor implements Executor
{
    private static final ThreadLocal<Boolean> EXECUTING = withInitial(() -> false);
    
    private final Queue<Runnable> actions;
    private final SeriallyRunnable serial;
    private final boolean mayRecurse;
    
    /**
     * Constructs a serial execute that never recurse.
     */
    public SerialExecutor() {
        this(false);
    }
    
    /**
     * Constructs a serial executor.
     * 
     * @param mayRecurse {@code true} or {@code false}
     */
    public SerialExecutor(boolean mayRecurse) {
        this.actions = new ConcurrentLinkedQueue<>();
        this.serial  = new SeriallyRunnable(this::pollAndExecute);
        this.mayRecurse = mayRecurse;
    }
    
    /**
     * Execute the given action or schedule it for future execution.
     * 
     * @param action to execute
     */
    @Override
    public void execute(Runnable action) {
        if (mayRecurse && EXECUTING.get()) {
            action.run();
        } else {
            actions.add(action);
            serial.run();
        }
    }
    
    private void pollAndExecute() {
        try {
            // if statement is merely a hint for the compiler/JIT to possibly
            // remove the use of EXECUTING if not necessary
            if (mayRecurse) EXECUTING.set(true);
            Runnable a;
            while ((a = actions.poll()) != null) {
                a.run();
            }
        } finally {
            if (mayRecurse) EXECUTING.set(false);
        }
    }
}