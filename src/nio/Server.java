package nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
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
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = keys.iterator();
                SelectionKey key;
                while (iterator.hasNext()) {
                    key = iterator.next();
                    if (key.isAcceptable()) {
                        //处理连接到服务器事件
                    } else if (key.isConnectable()) {
                        //连接到服务器
                    } else if (key.isReadable() || key.isWritable()) {
                        //处理读写
                    }
					/*
					 * 移除处理过的通道
					 * 因为selector不会自己移除处理过的，这也决定了只能用迭代器迭代
					 */
                    iterator.remove();
                }
            }
        }
    }

}
