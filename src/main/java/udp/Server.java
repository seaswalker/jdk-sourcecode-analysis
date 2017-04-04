package udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

/**
 * UDP服务器.
 *
 * @author skywalker
 */
public class Server {

    public static void main(String[] args) throws IOException {
        DatagramSocket server = new DatagramSocket(8080);

        byte[] buffer = new byte[1024];
        DatagramPacket packet = new DatagramPacket(buffer, 1024);

        server.receive(packet);

        System.out.println("Server接收到: " + new String(buffer, 0, packet.getLength()));

        //向客户端返回
        int port = packet.getPort();
        InetAddress address = packet.getAddress();
        byte[] data = "hello client".getBytes();
        DatagramPacket sendPacket = new DatagramPacket(data, data.length, address, port);
        server.send(sendPacket);

        server.close();
    }

}
