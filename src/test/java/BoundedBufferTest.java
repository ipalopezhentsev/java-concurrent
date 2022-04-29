import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedBufferTest {
    private static final Logger log = LoggerFactory.getLogger(BoundedBufferTest.class);

    @Test
    public void test() throws ExecutionException, InterruptedException {
        int elemsToProduce = 1_000_000;
        //Finished in PT3M12.0636893S:
//        final BaseBoundedBuffer<Integer> subj = new SleepyBoundedBuffer<>(100, 10);
        //Finished in PT21.3832531S:
//        final BaseBoundedBuffer<Integer> subj = new SleepyBoundedBuffer<>(100, 1);
        //Wow! Finished in PT0.525017S:
        final BaseBoundedBuffer<Integer> subj = new ConditionQueueBoundedBuffer<>(100);
        final var exec = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        var start = Instant.now();
        final Future<Long> futSumFromProducer = exec.submit(new Producer(elemsToProduce, subj));
        final Future<Long> futSumFromConsumer = exec.submit(new Consumer(elemsToProduce, subj));
        final long sumFromProducer = futSumFromProducer.get();
        log.info("Producer has finished");
        final long sumFromConsumer = futSumFromConsumer.get();
        log.info("Consumers has finished");
        assertThat(sumFromProducer).isEqualTo(sumFromConsumer);
        log.info("Finished in {}", Duration.between(start, Instant.now()));
        //TODO: make many parallel consumers
    }

    static class Producer implements Callable<Long> {
        private final int length;
        private final BaseBoundedBuffer<Integer> buf;

        Producer(int length, BaseBoundedBuffer<Integer> buf) {
            this.length = length;
            this.buf = buf;
        }

        @Override
        public Long call() throws Exception {
            long sum = 0;
            var rnd = new Random(0);
            for (int i = 0; i < length; i++) {
                if (i % 1_000 == 0) {
                    log.info("Inserting element #{}", i);
                }
                int r = rnd.nextInt();
                buf.put(r);
                sum += r;
            }
            return sum;
        }
    }

    static class Consumer implements Callable<Long> {
        private final BaseBoundedBuffer<Integer> buf;
        private final long length;

        Consumer(long length, BaseBoundedBuffer<Integer> buf) {
            this.buf = buf;
            this.length = length;
        }

        @Override
        public Long call() throws Exception {
            long sum = 0;
            for (int i = 0; i < length; i++) {
                if (i % 1_000 == 0) {
                    log.info("Taking element #{}", i);
                }
                sum += buf.take();
            }
            return sum;
        }
    }
}