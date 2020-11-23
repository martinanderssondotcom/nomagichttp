package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.ClosedPublisherException;
import alpha.nomagichttp.message.Request;

import java.util.concurrent.Flow;

import static alpha.nomagichttp.message.ClosedPublisherException.SIGNAL_FAILURE;
import static java.lang.System.Logger.Level.ERROR;
import static java.util.Objects.requireNonNull;

/**
 * On downstream signal failure; log the exception and close the channels read
 * stream, then pass the exception back to subscriber.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Request.Body
 */
final class OnErrorCloseReadStream<T> extends AbstractOp<T>
{
    private final ChannelOperations child;
    
    private static final System.Logger LOG
            = System.getLogger(OnErrorCloseReadStream.class.getPackageName());
    
    protected OnErrorCloseReadStream(Flow.Publisher<? extends T> upstream, ChannelOperations child) {
        super(upstream);
        this.child  = requireNonNull(child);
    }
    
    @Override
    protected void fromUpstreamNext(T item) {
        interceptThrowable(() -> signalNext(item));
    }
    
    @Override
    protected void fromUpstreamComplete() {
        interceptThrowable(this::signalComplete);
    }
    
    private void interceptThrowable(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            /*
              * Note, this isn't the only place where the read stream is closed
              * on an exceptional signal return. See also
              * ChannelByteBufferPublisher.subscriberAnnounce().
              * 
              * This class guarantees the behavior though, as not all paths to
              * the subscriber run through the subscriberAnnounce() method.
             */
            if (child.isOpenForReading()) {
                LOG.log(ERROR, SIGNAL_FAILURE + " Will close the channel's read stream.", t);
                child.orderlyShutdownInput();
            } // else assume whoever closed the stream also logged the exception
            
            signalError(new ClosedPublisherException(SIGNAL_FAILURE, t));
            throw t;
        }
    }
}