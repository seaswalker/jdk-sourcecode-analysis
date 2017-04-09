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
        //服务器绑定到特定的端口
        channel.socket().bind(new InetSocketAddress(8080));
        /*
         * 设为非阻塞模式，FileChannel不可设为此模式
		 */
        channel.configureBlocking(false);
        channel.register(selector, SelectionKey.OP_ACCEPT);
        while (true) {
			/*
			 * select()返回selector上就绪的通道的数量
			 */
            if (selector.select() > 0) {
				/*
				 * 返回就绪的通道集合
				 */
                System.out.println("select醒来");
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = keys.iterator();
                SelectionKey key;
                while (iterator.hasNext()) {
                    key = iterator.next();
                    if (key.isAcceptable()) {
                        System.out.println("可acept");
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
            } else {
                System.out.println("select 0");
            }
        }
    }

    private static void close(SocketChannel client) {
        new Thread(() -> {
            try {
                Thread.sleep(3000);
                System.out.println("执行关关闭: " + Thread.currentThread().getName());
                client.close();
                System.out.println("关闭结束");
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, "close-thread").start();
    }

}
