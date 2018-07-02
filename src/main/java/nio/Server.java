package nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

/**
 * NIO server.
 *
 * @author skywalker
 */
public class Server {

    public static void main(String[] args) throws IOException {
        Selector selector = Selector.open();
        ServerSocketChannel channel = ServerSocketChannel.open();

        channel.socket().bind(new InetSocketAddress(8080));
        // none-blocking mode
        channel.configureBlocking(false);
        channel.register(selector, SelectionKey.OP_ACCEPT);

        while (true) {
            if (selector.select() == 0) {
                continue;
            }

            Set<SelectionKey> keys = selector.selectedKeys();
            Iterator<SelectionKey> iterator = keys.iterator();

            SelectionKey key;
            while (iterator.hasNext()) {
                key = iterator.next();

                if (key.isAcceptable()) {
                    SocketChannel client = channel.accept();
                    System.out.println("客户端连接: " + client.getRemoteAddress());
                    client.configureBlocking(false);
                    client.register(selector, 0);
                }

                if (key.isWritable())
                    System.out.println("可写");

                if (key.isReadable())
                    System.out.println("可读");

                if (key.isConnectable())
                    System.out.println("可连接");

                if (key.isConnectable()) {
                    System.out.println("可连接");
                }

                iterator.remove();
            }
        }
    }

}
