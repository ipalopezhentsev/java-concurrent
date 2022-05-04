import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * more optimal implementation than IntrinsicConditionQueueBoundedBuffer:
 * it has two separate condition queues that allows to use signal() instead of signalAll()
 * to e.g. not awake a thread that waits for notFull condition by a thread that signals about
 * notEmpty condition.
 * @param <V>
 */
public class SeparateConditionQueuesBoundedBuffer<V> implements BoundedBuffer<V> {
    private final V[] buf;
    private int count, tail, head;
    private final Lock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    public SeparateConditionQueuesBoundedBuffer(int capacity) {
        buf = (V[]) new Object[capacity];
    }

    @Override
    public void put(V v) throws InterruptedException {
        try {
            lock.lock();
            while (count == buf.length) {
                notFull.await();
            }
            //here lock is held and buffer is not full
            buf[tail++] = v;
            if (tail == buf.length) {
                tail = 0;
            }
            count++;
            //signal to just one thread that was awaiting notEmpty condition
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public V take() throws InterruptedException {
        try {
            lock.lock();
            while (count == 0) {
                notEmpty.await();
            }
            //here lock is held and buffer is not empty
            final V v = buf[head];
            buf[head++] = null;
            count--;
            if (head == buf.length) {
                head = 0;
            }
            notFull.signal();
            return v;
        } finally {
            lock.unlock();
        }
    }
}
