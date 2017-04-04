package buffer;

import java.nio.ByteBuffer;

/**
 * @author skywalker
 */
public class BufferTest {

    public static void main(String[] args) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(4100);
        System.out.println(buffer.remaining());
    }

}
