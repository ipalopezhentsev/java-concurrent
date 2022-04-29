/**
 * Uses busy-wait to preserve state conditions.
 * It has two problems:
 * 1) if sleepMillis is small, lots of CPU will be consumed.
 * 2) if sleepMillis is long, it may oversleep after state really changed before noticing this
 */
public class SleepyBoundedBuffer<V> extends BaseBoundedBuffer<V> {
    private final long sleepMillis;

    public SleepyBoundedBuffer(int capacity, long sleepMillis) {
        super(capacity);
        this.sleepMillis = sleepMillis;
    }

    @Override
    public void put(V v) throws InterruptedException {
        while (true) {
            //we need synchronized due to 'check-then-act'
            synchronized(this) {
                if (!isFull()) {
                    doPut(v);
                    return;
                }
            }
            //we cannot hold the lock during sleep because no other thread will be able
            //to change 'not full' state if we do
            Thread.sleep(sleepMillis);
        }
    }

    @Override
    public V take() throws InterruptedException {
        while (true) {
            //we need synchronized due to 'check-then-act'
            synchronized (this) {
                if (!isEmpty()) {
                    return doTake();
                }
            }
            //we cannot hold the lock during sleep because no other thread will be able
            //to change 'not full' state if we do
            Thread.sleep(sleepMillis);
        }
    }
}
