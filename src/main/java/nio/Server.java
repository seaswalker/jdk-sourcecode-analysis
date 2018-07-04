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
        // register accept event
        channel.register(selector, SelectionKey.OP_ACCEPT);

        while (selector.select() != 0) {

            Set<SelectionKey> keys = selector.selectedKeys();
            Iterator<SelectionKey> iterator = keys.iterator();

            SelectionKey key;
            while (iterator.hasNext()) {
                key = iterator.next();

                if (key.isAcceptable()) {
                    SocketChannel client = channel.accept();
                    System.out.println("Client connected: " + client.getRemoteAddress());
                    client.configureBlocking(false);
                    client.register(selector, SelectionKey.OP_READ);
                }

                if (key.isWritable())
                    System.out.println("writable.");

                if (key.isReadable())
                    System.out.println("readable");

                iterator.remove();
            }
        }
    }

}
