package process;

import org.junit.Test;

import java.io.IOException;

/**
 * {@link Process}测试.
 *
 * @author skywalker
 */
public class ProcessTest {

    @Test
    public void ls() throws IOException, InterruptedException {
        Runtime runtime = Runtime.getRuntime();
        String cmd = "ls -l";
        Process process = runtime.exec(cmd);
        int value = process.waitFor();
    }

}
