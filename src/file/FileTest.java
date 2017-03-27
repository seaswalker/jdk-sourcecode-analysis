package file;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * 文件相关测试.
 *
 * @author skywalker
 */
public class FileTest {

    public static void main(String[] args) throws IOException {
        File file = new File("test");
        FileChannel channel = new FileOutputStream(file).getChannel();
        channel.write(ByteBuffer.wrap("hello".getBytes()));
        System.out.println("写入完成");
        channel.position(1 << 20);
        channel.truncate(1 << 20);
        channel.close();
    }

}
