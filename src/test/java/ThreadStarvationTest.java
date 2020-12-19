import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

public class ThreadStarvationTest {
    private static final Logger log = LoggerFactory.getLogger(ThreadStarvationTest.class);

    @Test
    public void testJoinsWithFixedPoolsCanCauseThreadStarvation() throws InterruptedException {
        //situation - if we have fixed thread pool (or one with limited queue which is supposed to grow when the queue
        //space ends, but we cannot grow it (e.g. if we use scheduled operation with fixed delay after end of previous
        //tick) then it's simply dangerous to call async api's with joins() - there is a number of parallel tasks that
        //cause all pool's threads to stuck on that join() without being able to finish tasks this join depends on
        //(as they sit in the queue and will never be processed).

        //let's choose pool of size 1 which is perfectly enough in theory to complete our task.
        final var schExSvc = new ScheduledThreadPoolExecutor(1);

        //note we use fixed delay, not fixed rate - means new task cannot start prior to finishing the first one.
        //it means if we are stuck we are stuck - new threads cannot fill queue of a pool so it cannot add new thread
        //(well it cannot do it in any case - ScheduledThreadPoolExecutor operates off an unbounded queue).
        final AtomicInteger tickNum = new AtomicInteger();
        final Runnable r = () -> {
            log.info("Start tick {}", tickNum.get());
            //suppose all we have is async api...
            CompletableFuture<Integer> fut = getFromAsyncOnlyApi(schExSvc);
            //but we want to call it periodically and with fixed delay between invocations, so we decide to wait
            //until the end (otherwise scheduleWithFixedDelay will schedule it immediately after we exit from this
            //function.
            //WARN: THIS IS A BAD PRACTICE THAT WILL LEAD TO STARVATION DEADLOCK (as getFromAsyncOnlyApi also needs
            //pool thread to finish its task!) See the next test case for the proper fix.
            fut.join();
            tickNum.incrementAndGet();
        };
        final ScheduledFuture<?> futSch = schExSvc.scheduleWithFixedDelay(r, 0, 1, TimeUnit.SECONDS);
        Thread.sleep(10_000L);
        //assert it didn't finish a single iteration due to thread starvation deadlock
        assertThat(tickNum.get()).isEqualTo(0);
        futSch.cancel(true);
        schExSvc.shutdown();
        final var isTerminatedBeforeTimeout = schExSvc.awaitTermination(5, TimeUnit.SECONDS);
        assertThat(isTerminatedBeforeTimeout).isFalse();
    }

    @Test
    public void testSolutionToPreviousProblem() throws InterruptedException {
        final var schExSvc = new ScheduledThreadPoolExecutor(1);
        final AtomicInteger tickNum = new AtomicInteger();
        //the solution is not join()ing but rescheduling manually when completable future finishes - see getRunnable()
        final Runnable r = getRunnable(schExSvc, tickNum);
        final ScheduledFuture<?> futSch = schExSvc.schedule(r, 0, TimeUnit.SECONDS);
        Thread.sleep(10_000L);
        //assert it didn't starve
        assertThat(tickNum.get()).isGreaterThan(0);
        futSch.cancel(true);
        schExSvc.shutdown();
        final var isTerminatedBeforeTimeout = schExSvc.awaitTermination(1, TimeUnit.DAYS);
        assertThat(isTerminatedBeforeTimeout).isTrue();
    }

    private Runnable getRunnable(ScheduledThreadPoolExecutor schExSvc, AtomicInteger tickNum) {
        return () -> {
            log.info("Start tick {}", tickNum.get());
            //suppose all we have is async api...
            getFromAsyncOnlyApi(schExSvc)
                    .whenComplete((res, e) -> {
                        final Runnable newRunnable = getRunnable(schExSvc, tickNum);
                        schExSvc.schedule(newRunnable, 1, TimeUnit.SECONDS);
                    });
            tickNum.incrementAndGet();
        };
    }

    @Test
    public void testThatThatPreviousSolutionIsLimitedOnlyToSchedules() throws InterruptedException {
        //i.e. it's impossible to provide non starving impl with pure async fun + join() when having fixed thread pool
        //that cannot create compensating threads while existing ones are blocked.

        //if we decrease it by just 1 it will start running fine - it means this approach creates dangerous
        //situations when stuff works on certain parallel tasks num and deadlocks under load.
        final int numTasks = Runtime.getRuntime().availableProcessors();
        final var nonGrowingPool = Executors.newFixedThreadPool(numTasks);
        //this will saturate the pool and cause thread starvation - because inside innocent looking
        //fauxSyncFromGetFromAsyncOnlyApi will submit a task to the same pool but a bit later than we saturate it -
        //and as it cannot create compensating thread it will deadlock due to thread starvation.
        final List<Future<Integer>> tasks = submitFutures(numTasks, nonGrowingPool);
        for (int i = 0; i < tasks.size(); i++) {
            log.info("Getting answer from task " + i);
            Future<Integer> task = tasks.get(i);
            try {
                task.get(3, TimeUnit.SECONDS);
                fail("not expected");
            } catch (InterruptedException | ExecutionException e) {
                fail("not expected");
            } catch (TimeoutException e) {
                log.info("timeout!");
                assertThat(e).isNotNull();
            }
        }
        nonGrowingPool.shutdown();
        final boolean isTerminatedBeforeTimeout = nonGrowingPool.awaitTermination(5, TimeUnit.SECONDS);
        //it was deadlocked...
        assertThat(isTerminatedBeforeTimeout).isFalse();
    }

    @Test
    public void testDemoThatWeCanFixPreviousTestWithPoolThatCreatesCompensatingThreadsOnSaturation() throws InterruptedException {
        final int numTasks = Runtime.getRuntime().availableProcessors();
        final var growingPool = ForkJoinPool.commonPool();
        //fork join pool can create temporary  compensating threads if it needs 'one more' thread
        //and all existing ones are blocked
        final List<Future<Integer>> tasks = submitFutures(numTasks, growingPool);
        for (int i = 0; i < tasks.size(); i++) {
            log.info("Getting answer from task " + i);
            Future<Integer> task = tasks.get(i);
            try {
                //delay must be greater than artificial delay in getFromAsyncOnlyApi_BlockingWait
                final Integer res = task.get(3, TimeUnit.SECONDS);
                assertThat(res).isEqualTo(42);
            } catch (InterruptedException | ExecutionException e) {
                fail("not expected");
            } catch (TimeoutException e) {
                log.info("timeout!");
                fail("not expected");
            }
        }
        //ForkJoinPool.commonPool() never terminates
        growingPool.shutdown();
        final boolean isTerminatedBeforeTimeout = growingPool.awaitTermination(1, TimeUnit.DAYS);
        //ForkJoinPool.commonPool() never terminates
        assertThat(isTerminatedBeforeTimeout).isFalse();
    }

    private List<Future<Integer>> submitFutures(int numTasks, ExecutorService pool) {
        final List<Future<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < numTasks; i++) {
            final Future<Integer> task = pool.submit(() -> {
                log.info("Starting task");
                //imagine this is old code that was expecting to call blocking sync api, then that api was removed
                //and 'sync' version fauxSyncFromGetFromAsyncOnlyApi() was written as 'replacement' - but it's
                //bad replacement since calling code never expected that its task would send blocking tasks to
                //the same pool - this is a recipe for thread starvation.
                return fauxSyncFromGetFromAsyncOnlyApi(pool);
            });
            tasks.add(task);
        }
        return tasks;
    }

    /**
     * Let's assume we have only non-blocking async api that makes a network call but then needs to
     * parse its response on a separate pool task (suppose it's heavy)
     */
    private CompletableFuture<Integer> getFromAsyncOnlyApi(ScheduledThreadPoolExecutor schExSvc) {
        CompletableFuture<String> fut = new CompletableFuture<>();
        //suppose we make some network call that takes a while
        schExSvc.schedule(() -> {
            fut.complete("42");
        }, 2, TimeUnit.SECONDS);
        return fut
                //let's assume we need to do some heavy processing after we receive answer from network svc -
                //i.e. imitate need for a spare thread in our pool - which in bad test won't be available
                //since all threads are busy with bad join() at the upper level
                .thenApplyAsync(Integer::parseInt, schExSvc);
    }

    private CompletableFuture<Integer>
    getFromAsyncOnlyApi_BlockingWait(ExecutorService schExSvc) throws InterruptedException {
        CompletableFuture<String> fut1 = new CompletableFuture<>();
        //let's assume we need to do some heavy processing after we receive answer from network svc -
        //i.e. imitate need for a spare thread in our pool - which in bad test won't be available
        //since all threads are busy with bad join() at the upper level
        final var fut2 = fut1.thenApplyAsync(Integer::parseInt, schExSvc);
        Thread.sleep(2000L);
        fut1.complete("42");
        return fut2;
    }

    /**
     * Suppose we had legacy function that expected blocking call giving the same result as value returned
     * from future returned from getFromAsyncOnlyApi(). Then imagine old blocking impl was removed and all we
     * now have is new great async function getFromAsyncOnlyApi. Then suppose we don't have easy way to make
     * asynchronous the old sync code that was calling the old sync version. So we decide to provide an adapter
     * and emulate old synchronous with join() on async one.
     * AND HERE IS THE PROBLEM - it's prone to thread starvation deadlocks with limited pools that cannot grow!
     */
    private Integer
    fauxSyncFromGetFromAsyncOnlyApi(ExecutorService exSvc) throws InterruptedException {
        return getFromAsyncOnlyApi_BlockingWait(exSvc)
                //can block all threads of a pool!
                .join();
    }
}
