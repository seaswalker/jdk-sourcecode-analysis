# SelectionKey

SelectionKey的类图如下:

![SelectionKey类图](images/SelectionKey.jpg)

注意，SelectionKey使用AtomicReferenceFieldUpdater进行原子更新attachment。

## 兴趣设置

SelectionKeyImpl.interestOps:

```java
public SelectionKey interestOps(int ops) {
    return nioInterestOps(ops);
}
```

nioInterestOps利用Channel来实现:

```java
public SelectionKey nioInterestOps(int ops) {
    channel.translateAndSetInterestOps(ops, this);
    interestOps = ops;
    return this;
}
```

//TODO

# Selector

官方的定义是SelectableChannel的多路复用器。类图:

![Selector类图](images/Selector.jpg)

AbstractSelector存在的意义是允许不同的服务提供者以平台相关的方式进行创建，比如在Windows平台上，完整的继承体系是这样:

![Selector_full.png](images/Selector_full.png)

## 创建

我们通常用下列方式进行创建:

```java
 Selector selector = Selector.open();
```

源码:

```java
public static Selector open() throws IOException {
    return SelectorProvider.provider().openSelector();
}
```

SelectorProvider定义了Selector的提供者。类图:

![SelectorProvider类图](images/SelectorProvider.jpg)

在Windows上的完整继承体系:

![provider_full](images/provider_full.png)

SelectorProvider的查找顺序如下:

- VM属性java.nio.channels.spi.SelectorProvider。

- 加载 系统服务，即jar包/META-INF/services文件夹下，这个默认是没有的。

- 默认方式:

  ```java
  provider = sun.nio.ch.DefaultSelectorProvider.create();
  ```

  在Windows上的实现便是:

  ```java
  public static SelectorProvider create() {
    return new WindowsSelectorProvider();
  }
  ```

  Linux实现:

  ```java
  public static SelectorProvider create() {
    String osname = AccessController
        .doPrivileged(new GetPropertyAction("os.name"));
    if (osname.equals("SunOS"))
        return createProvider("sun.nio.ch.DevPollSelectorProvider");
    if (osname.equals("Linux"))
        return createProvider("sun.nio.ch.EPollSelectorProvider");
    return new sun.nio.ch.PollSelectorProvider();
  }
  ```

  下面我们以Linux基准。

EPollSelectorProvider.openSelector:

```java
public AbstractSelector openSelector() throws IOException {
    return new EPollSelectorImpl(this);
}
```

## 通道注册

register方法在AbstractSelector中定义，SelectorImpl中实现:

```java
protected final SelectionKey register(AbstractSelectableChannel ch,int ops,Object attachment) {
    SelectionKeyImpl k = new SelectionKeyImpl((SelChImpl)ch, this);
    k.attach(attachment);
    synchronized (publicKeys) {
        implRegister(k);
    }
    k.interestOps(ops);
    return k;
}
```

publicKeys为一个Set，

## select

返回就绪的通道数，阻塞方法。SelectorImpl.select调用了select(0)方法:

```java
public int select(long timeout) {
    return lockAndDoSelect((timeout == 0) ? -1 : timeout);
}
```

# 通道

FileChannel我们已经见识过了，下面再来见识一下网络通道。两者的继承体系有相同的部分，从AbstractInterruptibleChannel开始分家，我们的类图就从AbstractInterruptibleChannel开始。

![AbstractInterruptibleChannel类图](images/AbstractInterruptibleChannel.jpg)

对于通道部分，仍以Windows作为参考，因为这样便于与Socket的底层实现进行对比。

## open

以ServerSocketChannel为例:

```java
public static ServerSocketChannel open() throws IOException {
    return SelectorProvider.provider().openServerSocketChannel();
}
```

实际上构造了一个	ServerSocketChannelImpl对象，构造方法:

```java
ServerSocketChannelImpl(SelectorProvider sp) {
    super(sp);
    this.fd =  Net.serverSocket(true);
    this.fdVal = IOUtil.fdVal(fd);
    this.state = ST_INUSE;
}
```

Net.serverSocket:

```java
static FileDescriptor serverSocket(boolean stream) {
    return IOUtil.newFD(socket0(isIPv6Available(), stream, true));
}
```

可见，此处其实创建了一个与之关联的ServerSocket对象。socket0实现位于Net.c中，同样是对Windows API socket的调用。IOUtil.newFD负责文件描述符的创建，并将其与socket0返回的地址相关联。

## bind

ServerSocketChannelImpl.bind简略版源码:

```java
@Override
public ServerSocketChannel bind(SocketAddress local, int backlog){
    synchronized (lock) {
        Net.bind(fd, isa.getAddress(), isa.getPort());
        Net.listen(fd, backlog < 1 ? 50 : backlog);
        synchronized (stateLock) {
            localAddress = Net.localAddress(fd);
        }
    }
    return this;
}
```

和Socket一样，同样是分为bind和listen两个步骤，分别对应Net的native方法bind0和listen。bind在底层实现其实是 两个操作: bind和设置端口占用的排他性。bind即Windows函数bind，位于jdk\src\windows\native\java\net\net_util_md.c:

```c
JNIEXPORT int JNICALL
NET_Bind(int s, struct sockaddr *him, int len) {
    int rv;
    rv = bind(s, him, len);
    if (rv == SOCKET_ERROR) {
       //...
    }
    return rv;
}
```

排他性设置由net_util_md.c的NET_SetSockOpt函数实现，简略版源码:

```c
JNIEXPORT int JNICALL
NET_SetSockOpt(int s, int level, int optname, const void *optval,int optlen){
    int rv;
    int parg;
    int plen = sizeof(parg);
    rv = setsockopt(s, level, optname, optval, optlen);
    if (rv == SOCKET_ERROR) {
        //...
    }
    return rv;
}
```

其实和Socket都是一套API.

## configureBlocking

由ServerSocketChannelImpl.implConfigureBlocking实现:

```java
protected void implConfigureBlocking(boolean block) throws IOException {
    IOUtil.configureBlocking(fd, block);
}
```

底层实现便是Windows的ioctlsocket函数，ServerSocket中，被用以实现带超时参数的accept，并未被用于实现非阻塞IO。

## selector注册

AbstractSelectableChannel.register简略版源码:

```java
public final SelectionKey register(Selector sel, int ops,Object att) {
    synchronized (regLock) {
        SelectionKey k = findKey(sel);
        if (k != null) {
            k.interestOps(ops);
            k.attach(att);
        }
        if (k == null) {
            // New registration
            synchronized (keyLock) {
                if (!isOpen())
                    throw new ClosedChannelException();
                k = ((AbstractSelector)sel).register(this, ops, att);
                //加入数组
                addKey(k);
            }
        }
        return k;
    }
}
```

方法将首先检查当前通道是否已经向给定的Selector注册过了 ，AbstractSelectableChannel内部维护有一个SelectionKey数组，findKey方法便是遍历此数组逐一比较其Selector的过程。

## 读

从类图中可以看出，只有SocketChannel才具有读写功能，ServerSocketChannel并不具备，这和Socket和ServerSocket的关系是一样的。

源码实现与FileChannle大体类似，但是有两点值得注意:

- 线程安全性，读的核心代码全部位于以下线程同步块中:

  ```java
  synchronized (readLock) {
    //code...
  }
  ```

  而写代码为writeLock，这也就验证了Channel线程安全的定义，同时说明**SocketChannel支持写和读之间的并行，但不能写与写、读与读之间并行**，这一特性其实是与TCP/IP协议相关的，所以，很容易可以推测FileChannel仅支持单线程读写，FileChannelImpl.read部分源码:

  ```java
  synchronized(positionLock) {//code...}
  ```

- 源码使用了字段readerThread来唤醒被阻塞的线程，在非阻塞模型下当然不会阻塞，被阻塞出现在configureBlocking(true)的情况。


Linux上的Java实现实际上是对系统调用read/write的封装，Java层面表现出来的特性其实是系统调用的反应。以写为例，在什么情况下会发生阻塞呢?对于TCP/IP协议栈，操作系统会为每个Socket维护一个发送缓冲区和一个接收缓冲区，所有待发送的数据应首先被放置到发送缓冲区中，但内核并不保证缓冲区中的数据一定会被发送出去，发送缓冲区的情况决定了写操作是否会被阻塞。

那么这个缓冲区一般有多大呢?在Linux上可以通过以下两个命令进行查看:

![wmem_default](images/wmem_default.png)

最大大小:

![wmem_max](images/wmem_max.png)

一般取值就在默认和最大之间，在我的ArchLinux虚拟机上两者是一样的，即160KB。

写操作在阻塞和非阻塞下的不同表现可总结如下:

- 如果发送缓冲区满，那么阻塞写将会阻塞，而非阻塞写返回0.
- 如果发送缓冲区不满，那么阻塞写会阻塞直到**发送缓冲区能够放下所有的待写数据**，而非阻塞写将返回**能够放下的字节数**。

对于阻塞写，还有一个非常有意思的细节，如果我们在写时如果连接已经断开，那么

而读操作**只要接收缓冲区中有数据就会返回，而不会等到接收缓冲区满**，不同表现总结如下:

- 如果读缓冲为空，那么阻塞读将会阻塞，非阻塞读会返回0.

读的特性可利用以下代码结合Linux nc命令很容易证明:

```java
@Test
public void nioRead() {
    SocketChannel channel = SocketChannel.open();
    channel.configureBlocking(false);
     //Linux nc监听地址
    channel.connect(new InetSocketAddress("192.168.80.128", 10010));
    while (channel.isConnectionPending()) {
        channel.finishConnect();
    }
    ByteBuffer buffer = ByteBuffer.allocate(10);
    int readed = channel.read(buffer);
    System.out.println(readed);
}
```

这里有一个以前没注意过的细节，对于非阻塞IO其connect方法也是非阻塞，也就是说，很有可能当我们调用read方法时连接还没有完成，所以我们需要循环调用isConnectionPending直到完成。

参考: [Unix/Linux中的read和write函数](http://www.cnblogs.com/xiehongfeng100/p/4619451.html)

## accept

ServerSocketChannelImpl.accept简略版源码:

```java
public SocketChannel accept() {
    synchronized (lock) {
        FileDescriptor newfd = new FileDescriptor();
        InetSocketAddress[] isaa = new InetSocketAddress[1];
        accept0(this.fd, newfd, isaa);
        IOUtil.configureBlocking(newfd, true);
        InetSocketAddress isa = isaa[0];
        sc = new SocketChannelImpl(provider(), newfd, isa);
        return sc;
    }
}
```

accept0为native方法，可以看出，其负责设置了新的文件描述符，创建ScoketChannel对象，注意，默认为阻塞模式。底层实现仍为accept函数，和ServerSocket一样。



http://www.cnblogs.com/promise6522/archive/2012/03/03/2377935.html

http://www.cnblogs.com/Solstice/archive/2011/05/04/2036983.html

http://blog.csdn.net/Solstice/article/category/642322



