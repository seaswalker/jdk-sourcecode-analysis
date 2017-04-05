# SelectionKey

SelectionKey的类图如下:

![SelectionKey类图](images/SelectionKey.jpg)

注意，SelectionKey使用AtomicReferenceFieldUpdater进行原子更新attachment，这货就像是Channel和Selector结合的结晶。

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

SocketChannelImpl.translateAndSetInterestOps:

```java
public void translateAndSetInterestOps(int ops, SelectionKeyImpl sk) {
    int newOps = 0;
    if ((ops & SelectionKey.OP_READ) != 0)
        newOps |= Net.POLLIN;
    if ((ops & SelectionKey.OP_WRITE) != 0)
        newOps |= Net.POLLOUT;
    if ((ops & SelectionKey.OP_CONNECT) != 0)
        newOps |= Net.POLLCONN;
    sk.selector.putEventOps(sk, newOps);
}
```

POLLIN等便是Linux epoll事件，EPollSelectorImpl.putEventOps:

```java
public void putEventOps(SelectionKeyImpl ski, int ops) {
    SelChImpl ch = ski.channel;
    pollWrapper.setInterest(ch.getFDVal(), ops);
}
```

在Linux的实现中pollWrapper是一个EPollArrayWrapper类型，为epoll_event结构体的封装。setInterest方法的实际实现由setUpdateEvents完成:

```java
private void setUpdateEvents(int fd, byte events, boolean force) {
    if (fd < MAX_UPDATE_ARRAY_SIZE) {
        if ((eventsLow[fd] != KILLED) || force) {
            eventsLow[fd] = events;
        }
    } else {
        Integer key = Integer.valueOf(fd);
        if (!isEventsHighKilled(key) || force) {
            eventsHigh.put(key, Byte.valueOf(events));
        }
    }
}
```

## 取消

cancel方法用于通道到Selector的注册，AbstractSelectionKey.cancel:

```java
public final void cancel() {
    synchronized (this) {
        if (valid) {
            valid = false;
            ((AbstractSelector)selector()).cancel(this);
        }
    }
}
```

AbstractSelector.cancel:

```java
void cancel(SelectionKey k) {                   
    synchronized (cancelledKeys) {
        cancelledKeys.add(k);
    }
}
```

cancelledKeys是一个Set，可以看出，cancel方法的调用并不会导致Selector的立即响应，参见Selector-select一节。

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

publicKeys为一个Set，保存着注册在当前Selector的key，EPollSelectorImpl.implRegister:

```java
protected void implRegister(SelectionKeyImpl ski) {
    SelChImpl ch = ski.channel;
    int fd = Integer.valueOf(ch.getFDVal());
    //文件描述符到key的映射
    fdToKey.put(fd, ski);
    pollWrapper.add(fd);
    //Set
    keys.add(ski);
}
```

### 文件描述符

方法里的fd为一个int形式的文件描述符的值，这到底是个什么东西呢?通道内有一个fdVal属性，即为此值，在其构造器中初始化:

```java
SocketChannelImpl(SelectorProvider sp) {
    this.fd = Net.socket(true);
    this.fdVal = IOUtil.fdVal(fd);
}
```

IOUtil的fdVal为native实现:

```c
jint fdval(JNIEnv *env, jobject fdo) {
    return (*env)->GetIntField(env, fdo, fd_fdID);
}
```

获得其实就是FileDescriptor的int型字段fd。回顾Socket的创建或是下面通道-open一节，可以发现这个值其实就是系统调用socket返回的值，此值便是**套接字描述符**，其实就是系统为每个进程维护的文件描述符表(数组)的下标(索引)，并且是递增的。为什么要这么设计呢，因为使用套接字的时候不可避免的需要用到IP协议 版本，远程地址等一系列属性，套接字描述符为这些属性的获取、设置与传递提供了便利。参考:

[Linux的SOCKET编程详解](http://blog.csdn.net/hguisu/article/details/7445768/)

### 事件占位

EPollArrayWrapper.add:

```java
void add(int fd) {
    synchronized (updateLock) {
        assert !registered.get(fd);
        setUpdateEvents(fd, (byte)0, true);
    }
}
```

setUpdateEvents方法参见SelectionKey-兴趣设置，叫占位的原因就是真正的事件设置由SelectionKey完成。

## select

返回就绪的通道数，阻塞方法。SelectorImpl.select调用了select(0)方法:

```java
public int select(long timeout) {
    return lockAndDoSelect((timeout == 0) ? -1 : timeout);
}
```

SelectorImpl.lockAndDoSelect:

```java
private int lockAndDoSelect(long timeout) {
    synchronized (this) {
        synchronized (publicKeys) {
            synchronized (publicSelectedKeys) {
                return doSelect(timeout);
            }
        }
    }
}
```

EPollSelectorImpl.doSelect:

```java
protected int doSelect(long timeout) {
    processDeregisterQueue();
    try {
        begin();
        pollWrapper.poll(timeout);
    } finally {
        end();
    }
    processDeregisterQueue();
    int numKeysUpdated = updateSelectedKeys();
    if (pollWrapper.interrupted()) {
        // Clear the wakeup pipe
        pollWrapper.putEventOps(pollWrapper.interruptedIndex(), 0);
        synchronized (interruptLock) {
            pollWrapper.clearInterrupted();
            IOUtil.drain(fd0);
            interruptTriggered = false;
        }
    }
    return numKeysUpdated;
}
```

### 取消事件处理

明显可以看出，pollWrapper.poll(timeout)是select操作的核心，也是可能发生阻塞的地方，在poll操作的两侧各有一次取消事件处理的入口(processDeregisterQueue):

```java
void processDeregisterQueue() throws IOException {
    // Precondition: Synchronized on this, keys, and selectedKeys
    Set<SelectionKey> cks = cancelledKeys();
    synchronized (cks) {
        if (!cks.isEmpty()) {
            Iterator<SelectionKey> i = cks.iterator();
            while (i.hasNext()) {
                SelectionKeyImpl ski = (SelectionKeyImpl)i.next();
                try {
                    implDereg(ski);
                } catch (SocketException se) {
                    throw new IOException("Error deregistering key", se);
                } finally {
                    i.remove();
                }
            }
        }
    }
}
```

遍历+移除的过程，取消的核心逻辑位于EPollSelectorImpl.implDereg:

```java
protected void implDereg(SelectionKeyImpl ski) {
    assert (ski.getIndex() >= 0);
    SelChImpl ch = ski.channel;
    int fd = ch.getFDVal();
    fdToKey.remove(Integer.valueOf(fd));
    pollWrapper.remove(fd);
    ski.setIndex(-1);
    keys.remove(ski);
    selectedKeys.remove(ski);
    //从Channel的key集合中移除
    deregister((AbstractSelectionKey)ski);
    SelectableChannel selch = ski.channel();
    //如果通道已关闭且没有注册到任何Selector中
    if (!selch.isOpen() && !selch.isRegistered())
        ((SelChImpl)selch).kill();
}
```

如果判断通道有没有注册到Selector中呢，其实很简单, AbstractSelectableChannel.isRegistered:

```java
public final boolean isRegistered() {
    synchronized (keyLock) {
        return keyCount != 0;
    }
}
```

keyCount被addKey方法增加，参考通道-Selector注册一节。

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

- 源码使用了字段readerThread来唤醒被阻塞的线程，那么这里的阻塞到底指的什么呢?其实从系统层面上来说，一个读的过程可以分为两部分:

  - 从网络、文件读取。
  - 内核将读到的数据从内核空间拷贝到用户空间交于用户程序使用。

  Java的阻塞IO与非阻塞IO(NIO)是针对第一个阶段而言的，第二个阶段仍是阻塞的。


Java阻塞IO与非阻塞IO的表现可总结 如下: 

Linux上的Java实现实际上是对系统调用read/write的封装，Java层面表现出来的特性其实是系统调用的反应。以写为例，在什么情况下会发生阻塞呢?对于TCP/IP协议栈，操作系统会为每个Socket维护一个发送缓冲区和一个接收缓冲区，所有待发送的数据应首先被放置到发送缓冲区中，但内核并不保证缓冲区中的数据一定会被发送出去，发送缓冲区的情况决定了写操作是否会被阻塞。

那么这个缓冲区一般有多大呢?在Linux上可以通过以下两个命令进行查看:

![wmem_default](images/wmem_default.png)

最大大小:

![wmem_max](images/wmem_max.png)

一般取值就在默认和最大之间，在我的ArchLinux虚拟机上两者是一样的，即160KB。

写操作在阻塞和非阻塞下的不同表现可总结如下:

- 如果发送缓冲区满，那么阻塞写将会阻塞，而非阻塞写返回0.
- 如果发送缓冲区不满，那么阻塞写会阻塞直到**发送缓冲区能够放下所有的待写数据**，而非阻塞写将返回**能够放下的字节数**。

对于阻塞写，还有一个非常有意思的细节，如果我们在写时如果连接已经断开，那么将会把发送缓冲填满并返回填入的字节数，**在第二次写的时候才会报错**，这个问题在实际中遇到了。

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

这里有一个以前没注意过的细节，对于非阻塞IO其connect方法也是非阻塞，也就是说，很有可能当我们调用read方法时连接还没有完成，所以我们需要循环调用isConnectionPending直到完成。参考:

 [Unix/Linux中的read和write函数](http://www.cnblogs.com/xiehongfeng100/p/4619451.html)

[Linux IO模式及 select、poll、epoll详解](https://segmentfault.com/a/1190000003063859)

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

## 关闭

SocketChannel和FileChannel一样都是AbstractInterruptibleChannel的子类，所以close方法的实现是一样的:

```java
public final void close(){
    synchronized (closeLock) {
        if (!open)
            return;
        open = false;
        implCloseChannel();
    }
}
```

这里只是修改了开启标志位，很容易想到，SocketChannel关闭的核心是以下两步:

- 取消注册的key.
- 如果有线程阻塞在读或写上。

AbstractSelectableChannel.implCloseChannel:

```java
protected final void implCloseChannel() {
    implCloseSelectableChannel();
    //取消所有的key
    synchronized (keyLock) {
        int count = (keys == null) ? 0 : keys.length;
        for (int i = 0; i < count; i++) {
            SelectionKey k = keys[i];
            if (k != null)
                k.cancel();
        }
    }
}
```

SocketChannelImpl.implCloseSelectableChannel:

```java
protected void implCloseSelectableChannel() {
    synchronized (stateLock) {
        isInputOpen = false;
        isOutputOpen = false;
        if (state != ST_KILLED)
            nd.preClose(fd);
        //如果有线程阻塞在写/读上，那么唤醒之，这么做的原因是在Linux上关闭文件描述符并不会导致线程被唤醒，所以
        //需要手动唤醒，这一点在FileChannel关闭中也提到了
        if (readerThread != 0)
            NativeThread.signal(readerThread);
        if (writerThread != 0)
            NativeThread.signal(writerThread);
        //如果当前Channel不再被Select使用，那么kill掉，为什么不直接Kill呢，因为对SelectionKey.cancel方法的
        //调用同样会导致kill被调用
        if (!isRegistered())
            kill();
    }
}
```

kill方法:

```java
public void kill() throws IOException {
    synchronized (stateLock) {
        if (state == ST_KILLED)
            return;
        if (state == ST_UNINITIALIZED) {
            state = ST_KILLED;
            return;
        }
        assert !isOpen() && !isRegistered();
        // Postpone the kill if there is a waiting reader
        // or writer thread. See the comments in read() for
        // more detailed explanation.
        if (readerThread == 0 && writerThread == 0) {
            nd.close(fd);
            state = ST_KILLED;
        } else {
            state = ST_KILLPENDING;
        }
    }
}
```

我们可以用如下方式对文件描述符进行验证，假设有命令`nc -l -p 10010`对10010端口进行监听，我们使用简单的Java连接此端口，使用ps命令得到此nc的进程号，在/proc/进程号/fd便是此进程拥有的文件描述符，如下图(ls -lh):

![文件描述符](images/fd.png)

0,1,2是Unix中标准的文件描述符号，其意义如下:

| 文件描述符 | 用途   | POSIX名称       | stdio流 |
| ----- | ---- | ------------- | ------ |
| 0     | 标准输入 | STDIN_FILENO  | stdin  |
| 1     | 标准输出 | STDOUT_FILENO | stdout |
| 2     | 标准错误 | STDERR_FILENO | stderr |

参考: [每天进步一点点——Linux中的文件描述符与打开文件之间的关系](http://blog.csdn.net/cywosp/article/details/38965239)