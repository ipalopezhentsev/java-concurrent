import net.jcip.annotations.ThreadSafe;

/**
 * Thread safe bounded buffer with finite capacity that allows efficient working from multiple
 * produce-consumer threads.
 * just follows spirit of example from Java Concurrency in practice
 */
@ThreadSafe
public abstract class BaseBoundedBuffer<V> {
    private final V[] buf;
    private int head;
    private int tail;
    private int length;

    public BaseBoundedBuffer(int capacity) {
        buf = (V[]) new Object[capacity];
    }

    /**
     * puts an element to back of buffer or blocks caller while buffer is full.
     */
    public abstract void put(V v) throws InterruptedException;

    /**
     * takes an element from the head of buffer or blocks caller while buffer is empty.
     */
    public abstract V take() throws InterruptedException;

    /**
     * INVARIANT: buffer must be not full
     */
    protected synchronized void doPut(V v) {
        buf[tail++] = v;
        if (tail == buf.length) {
            tail = 0;
        }
        length++;
    }

    /**
     * INVARIANT: buffer must be not empty
     */
    protected synchronized V doTake() {
        V v = buf[head];
        buf[head++] = null;
        if (head == buf.length) {
            head = 0;
        }
        length--;
        return v;
    }


    //synchronized to make sure we see update to length from another thread
    protected synchronized boolean isEmpty() {
        return length == 0;
    }

    protected synchronized boolean isFull() {
        return length == buf.length;
    }
}
