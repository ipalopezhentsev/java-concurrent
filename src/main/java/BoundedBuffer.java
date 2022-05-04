public interface BoundedBuffer<V> {
    /**
     * puts an element to back of buffer or blocks caller while buffer is full.
     */
    public abstract void put(V v) throws InterruptedException;

    /**
     * takes an element from the head of buffer or blocks caller while buffer is empty.
     */
    public abstract V take() throws InterruptedException;
}
