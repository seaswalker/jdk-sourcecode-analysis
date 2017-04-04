package file;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

/**
 * 文件相关测试.
 *
 * @author skywalker
 */
public class FileTest {

    public static void main(String[] args) throws IOException {
        File file = new File("test");
        FileChannel channel = new RandomAccessFile(file, "rw").getChannel();
        new Thread(new GETLock(channel, 0, 2)).start();
        new Thread(new GETLock(channel, 1, 3)).start();
        //channel.close();
    }

    private static class GETLock implements Runnable {

        private final FileChannel channel;
        private final int start;
        private final int end;

        private GETLock(FileChannel channel, int start, int end) {
            this.channel = channel;
            this.start = start;
            this.end = end;
        }

        @Override
        public void run() {
            try {
                FileLock lock = channel.lock(start, end, true);
                System.out.println(Thread.currentThread().getName() + "获得锁");
                Thread.sleep(2000);
                lock.release();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

    }

}
