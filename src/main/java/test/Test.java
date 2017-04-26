package test;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.concurrent.ConcurrentLinkedQueue;

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
        ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
        queue.offer("a");
        queue.offer("b");
    }

}
