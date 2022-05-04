/**
 * Unlike SleepyBoundedBuffer:
 * 1) does not spin CPU unnecessarily waiting for condition (buffer not full/empty) to fulfil
 * 2) does not oversleep - e.g. when a thread waits for full buffer to become not full and another thread
 * takes an element, the original thread will wake up really soon after this.
 */
public class IntrinsicConditionQueueBoundedBuffer<V> extends BaseBoundedBuffer<V> {
    public IntrinsicConditionQueueBoundedBuffer(int capacity) {
        super(capacity);
    }

    @Override
    //we need synchronized because condition queue methods must be called with lock taken
    public synchronized void put(V v) throws InterruptedException {
        //we should check after every wakeup from wait() because of spurious wakeups or other threads which were
        //also waiting on our monitor but acquired object lock faster than us and already changed the state on
        //which we depend. Note isFull() will always be called with monitor locked so we see the changes to
        //state variables.
        while (isFull()) {
            //unlocks monitor - so other threads can change the state and call notify() -
            //before returning, wait() will reacquire the lock
            wait();
        }
        //here we are:
        //1) with monitor locked, so we can change the state,
        //2) with our state condition fulfilled ("buffer is not full")
        doPut(v);
        //our condition queue tracks two events - buffer is full/buffer is empty.
        //after we've put an element, buffer is no longer empty if it was prior to it - let's
        //notify other threads which may be waiting for 'not empty' event.
        //We must call notify with monitor still held.
        //We must call notifyAll instead of notify because we track to events via one condition queue
        //and if we notify() to a thread that was waiting for 'the other' event, no other thread that
        //was waiting for 'this' event would get it and so it will be lost.
        notifyAll();
    }

    @Override
    //we need synchronized because condition queue methods must be called with lock taken
    public synchronized V take() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        final V v = doTake();
        //let threads waiting for 'not full' event wakeup and check it
        notifyAll();
        return v;
    }
}
