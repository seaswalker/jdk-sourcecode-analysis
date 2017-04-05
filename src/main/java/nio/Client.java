package nio;

import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Arrays;

/**
 * NIO Client.
 *
 * @author skywalker
 */
public class Client {

    /**
     * 测试NIO客户端的阻塞表现.
     */
    @Test
    public void nioRead() throws IOException {
        SocketChannel channel = SocketChannel.open();
        channel.configureBlocking(true);
        channel.connect(new InetSocketAddress("192.168.80.128", 10010));
        while (channel.isConnectionPending()) {
            channel.finishConnect();
        }
        ByteBuffer buffer = ByteBuffer.allocate(10);
        int readed = channel.read(buffer);
        System.out.println(readed);
    }

    /**
     * 测试阻塞/非阻塞的写.
     */
    public static void main(String[] args) throws IOException {
        SocketChannel channel = SocketChannel.open();
        channel.configureBlocking(true);
        channel.connect(new InetSocketAddress("192.168.80.128", 10010));
        while (channel.isConnectionPending()) {
            channel.finishConnect();
        }
        byte[] dirty = new byte[163832];
        byte[] data = new byte[10];
        Arrays.fill(dirty, (byte) 'a');
        Arrays.fill(data, (byte) 'a');
        //脏数据
        channel.write(ByteBuffer.wrap(dirty));
        int writed = channel.write(ByteBuffer.wrap(data));
        System.out.println(writed);
    }

}
