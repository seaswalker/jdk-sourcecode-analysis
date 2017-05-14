package test;

import java.text.ParseException;
import java.util.concurrent.*;

/**
 * Test something.
 *
 * @author skywalker
 */
public class Test {

    /**
     * Test {@link Integer#numberOfLeadingZeros(int)}.
     */
    @org.junit.Test
    public void leadingZeroes() throws ParseException {
        System.out.println(Integer.numberOfLeadingZeros(16));
        System.out.println(146 / 95.6666666667 >= 1.5);
    }

    @org.junit.Test
    public void linkedQueue() {
        SomeQueue<String> queue = new SomeQueue<>();
        queue.offer("a");
        System.out.println(queue.poll());
    }

    @org.junit.Test
    public void threadPool() throws InterruptedException {
        ThreadPoolExecutor service = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        service.execute(() -> {
            throw new RuntimeException();
        });
        Thread.sleep(2000);
        System.out.println(service.getPoolSize());
    }

    @org.junit.Test
    public void maxPoolSize() throws InterruptedException {
        ThreadPoolExecutor service = (ThreadPoolExecutor) new ThreadPoolExecutor(1, 3, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(1));
        service.execute(new StupidTask(1));
        service.execute(new StupidTask(2));
        service.execute(new StupidTask(3));
        Thread.sleep(7000);
        System.out.println(service.getPoolSize());
        System.out.println(service.getLargestPoolSize());
    }

    @org.junit.Test
    public void threadLocal() {
        ThreadLocal<String> local = ThreadLocal.withInitial(() -> "hello");
    }

    private class StupidTask implements Runnable {

        private final int id;

        private StupidTask(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            System.out.println("hello" + id + ": " + Thread.currentThread().getName());
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

}
