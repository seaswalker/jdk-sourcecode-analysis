我们对JDK1.8版本的ConcurrentHashMap进行说明，1.8版本的ConcurrentHashMap相比之前的版本主要做了两处改进:

- 使用CAS代替分段锁。
- 红黑树，这一点和HashMap是一致的。


# put

最核心的便是put方法:

```java
public V put(K key, V value) {
    return putVal(key, value, false);
}
```

最后一个参数为onlyIfAbsent，表示只有在key对应的value不存在时才将value加入，所以putVal是put和putIfAbsent两个方法的真正实现。

```java
final V putVal(K key, V value, boolean onlyIfAbsent) {
    if (key == null || value == null) throw new NullPointerException();
    int hash = spread(key.hashCode());
    int binCount = 0;
    //volatile读
    for (Node<K,V>[] tab = table;;) {
        Node<K,V> f; int n, i, fh;
        if (tab == null || (n = tab.length) == 0)
            //初始化
            tab = initTable();
        else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
            if (casTabAt(tab, i, null,
                         new Node<K,V>(hash, key, value, null)))
                break;                   // no lock when adding to empty bin
        }
        else if ((fh = f.hash) == MOVED)
            tab = helpTransfer(tab, f);
        else {
            //节点添加
            V oldVal = null;
            synchronized (f) {
                if (tabAt(tab, i) == f) {
                    if (fh >= 0) {
                        binCount = 1;
                        for (Node<K,V> e = f;; ++binCount) {
                            K ek;
                            if (e.hash == hash &&
                                ((ek = e.key) == key ||
                                 (ek != null && key.equals(ek)))) {
                                oldVal = e.val;
                                if (!onlyIfAbsent)
                                    e.val = value;
                                break;
                            }
                            Node<K,V> pred = e;
                            if ((e = e.next) == null) {
                                pred.next = new Node<K,V>(hash, key, value, null);
                                break;
                            }
                        }
                    }
                    else if (f instanceof TreeBin) {
                        Node<K,V> p;
                        binCount = 2;
                        if ((p = ((TreeBin<K,V>)f).putTreeVal(hash, key, value)) != null) {
                            oldVal = p.val;
                            if (!onlyIfAbsent)
                                p.val = value;
                        }
                    }
                }
            }
            if (binCount != 0) {
                if (binCount >= TREEIFY_THRESHOLD)
                    treeifyBin(tab, i);
                if (oldVal != null)
                    return oldVal;
                break;
            }
        }
    }
    addCount(1L, binCount);
    return null;
}
```

table便是其数据的存放载体:

```java
transient volatile Node<K,V>[] table;
```

它是volatile的。

## 初始化

如果table为空或大小为0，那么将对其进行初始化操作，initTable:

```java
private final Node<K,V>[] initTable() {
    Node<K,V>[] tab; int sc;
    //volatile读
    while ((tab = table) == null || tab.length == 0) {
        //volatile读
        if ((sc = sizeCtl) < 0)
            Thread.yield(); // lost initialization race; just spin
        else if (U.compareAndSwapInt(this, SIZECTL, sc, -1)) {
            try {
                if ((tab = table) == null || tab.length == 0) {
                    int n = (sc > 0) ? sc : DEFAULT_CAPACITY;
                    Node<K,V>[] nt = (Node<K,V>[])new Node<?,?>[n];
                    table = tab = nt;
                    //sizeCtl设为当前大小的3 / 4
                    sc = n - (n >>> 2);
                }
            } finally {
                sizeCtl = sc;
            }
            break;
        }
    }
    return tab;
}
```

sizeCtl是ConcurrentHashMap的初始化，扩容操作中一个至关重要的控制变量，其声明:

```java
private transient volatile int sizeCtl;
```

其取值可能为:

- 0: 初始值。


- -1: 正在进行初始化。
- 负值(小于-1): 表示正在进行扩容，因为ConcurrentHashMap支持多线程并行扩容。
- 正数: 表示下一次触发扩容的临界值大小，即当前值 * 0.75(负载因子)。

从源码中可以看出，ConcurrentHashMap只允许一个线程进行初始化操作，当其它线程竞争失败(sizeCtl < 0)时便会进行自旋，直到竞争成功(初始化)线程完成初始化，那么此时table便不再为null，也就退出了while循环。

Thread.yield方法用于提示CPU可以放弃当前线程的执行，当然这只是一个提示(hint)，这里对此方法的调用是一个优化手段。

对SIZECTL字段CAS更新的成功便标志者线程赢得了竞争，可以进行初始化工作了，剩下的就是一个数组的构造过程，一目了然。

## 头结点设置

如果key对应的bin为空，那么我们只需要将给定的节点 设为头结点即可，这里对应putVal源码中的下面的部分:

```java
else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
    if (casTabAt(tab, i, null, new Node<K,V>(hash, key, value, null)))
        break;
}
```

这里tabAt是一次volatile读，casTabAt为CAS操作。

## 节点添加

如果key对应的bin不为 null，那么就说明需要进行节点添加，从源码可以看出，这里对bin的头结点进行了加锁操作。我的理解为，这里需要**遍历整个链表或搜索红黑树以判断给定的节点(值)是否已存在，同时需要记录链表节点的个数，以决定是否需要将其转化为红黑树**。

## 转为红黑树

指putVal源码中的:

```java
if (binCount != 0) {
    if (binCount >= TREEIFY_THRESHOLD)
        treeifyBin(tab, i);
    if (oldVal != null)
        return oldVal;
    break;
}
```

注意，这段代码是在上述(节点添加部分)同步代码块之外执行的。

TREEIFY_THRESHOLD表示将链表转为红黑树的链表长度的临界值，默认为8.

```java
private final void treeifyBin(Node<K,V>[] tab, int index) {
    Node<K,V> b; int n, sc;
    if (tab != null) {
        if ((n = tab.length) < MIN_TREEIFY_CAPACITY)
            //扩容
            tryPresize(n << 1);
        else if ((b = tabAt(tab, index)) != null && b.hash >= 0) {
            synchronized (b) {
                if (tabAt(tab, index) == b) {
                    TreeNode<K,V> hd = null, tl = null;
                    for (Node<K,V> e = b; e != null; e = e.next) {
                        TreeNode<K,V> p = new TreeNode<K,V>(e.hash, e.key, e.val, null, null);
                        if ((p.prev = tl) == null)
                            hd = p;
                        else
                            tl.next = p;
                        tl = p;
                    }
                    setTabAt(tab, index, new TreeBin<K,V>(hd));
                }
            }
        }
    }
}
```

### 扩容

如果当前bin的个数未达到MIN_TREEIFY_CAPACITY，那么不再转为红黑树，转而进行扩容。MIN_TREEIFY_CAPACITY默认为64.tryPresize:

```java
private final void tryPresize(int size) {
    int c = (size >= (MAXIMUM_CAPACITY >>> 1)) ? MAXIMUM_CAPACITY :
        tableSizeFor(size + (size >>> 1) + 1);
    int sc;
    //volatile读，没有正在进行初始化或扩容的操作
    while ((sc = sizeCtl) >= 0) {
        Node<K,V>[] tab = table; int n;
        //这里实际上进行了初始化工作
        if (tab == null || (n = tab.length) == 0) {
            n = (sc > c) ? sc : c;
            if (U.compareAndSwapInt(this, SIZECTL, sc, -1)) {
                try {
                    if (table == tab) {
                        Node<K,V>[] nt = (Node<K,V>[])new Node<?,?>[n];
                        table = nt;
                        sc = n - (n >>> 2);
                    }
                } finally {
                    sizeCtl = sc;
                }
            }
        }
        //已达到最大值，无法再进行扩容
        else if (c <= sc || n >= MAXIMUM_CAPACITY)
            break;
        else if (tab == table) {
            int rs = resizeStamp(n);
            if (sc < 0) {
                //竞争失败
                Node<K,V>[] nt;
                //判断是否已经完成
                if ((sc >>> RESIZE_STAMP_SHIFT) != rs || sc == rs + 1 ||
                    sc == rs + MAX_RESIZERS || (nt = nextTable) == null || transferIndex <= 0)
                    break;
                if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1))
                    transfer(tab, nt);
            }
            //竞争成功
            else if (U.compareAndSwapInt(this, SIZECTL, sc, (rs << RESIZE_STAMP_SHIFT) + 2))
                transfer(tab, null);
        }
    }
}
```

前面提到过了，ConcurrentHashMap支持多线程并行扩容，具体来说，是支持**多线程将节点从老的数组拷贝到新的数组**，而新数组创建仍是一个线程完成(不然多个线程创建多个对象，最后只使用一个，这不是浪费是什么?)

竞争成功的线程为transfer方法的nextTab参数传入null，这将导致新数组的创建。竞争失败的线程将会判断当前节点转移工作是否已经完成，如果已经完成，那么意味着扩容的完成，退出即可，如果没有完成，那么此线程将会进行辅助转移。

判断是否已经完成的条件只能理解(nt = nextTable) == null || transferIndex <= 0两个。

#### 转移

```java
private final void transfer(Node<K,V>[] tab, Node<K,V>[] nextTab) {
    int n = tab.length, stride;
    //1. 分片
    if ((stride = (NCPU > 1) ? (n >>> 3) / NCPU : n) < MIN_TRANSFER_STRIDE)
        stride = MIN_TRANSFER_STRIDE; // subdivide range
    //nextTab初始化，CAS保证了只会有一个线程执行这里的代码
    if (nextTab == null) {
        try {
            Node<K,V>[] nt = (Node<K,V>[])new Node<?,?>[n << 1];
            nextTab = nt;
        } catch (Throwable ex) {      // try to cope with OOME
            sizeCtl = Integer.MAX_VALUE;
            return;
        }
        nextTable = nextTab;
        transferIndex = n;
    }
    int nextn = nextTab.length;
    ForwardingNode<K,V> fwd = new ForwardingNode<K,V>(nextTab);
    boolean advance = true;
    boolean finishing = false; // to ensure sweep before committing nextTab
    for (int i = 0, bound = 0;;) {
        Node<K,V> f; int fh;
        while (advance) {
            int nextIndex, nextBound;
            //分片的最大下标i实际上就是在这里完成减一的，因为从下面可以看出，每处理完一个桶位便将advance设为true			 //从而便又进入了内层循环，但是注意，当最后一次(即bound)处理完成时，i会被再次减一，从而导致进入下面的			//分支再次读取transferIndex，这就说明了转移线程会在转移完一个分片后继续尝试剩余的分片(桶位)
            if (--i >= bound || finishing)
                advance = false;
            else if ((nextIndex = transferIndex) <= 0) {
                //所有bin均转移完毕
                i = -1;
                advance = false;
            }
            //申请分片
            else if (U.compareAndSwapInt
                     (this, TRANSFERINDEX, nextIndex,
                      nextBound = (nextIndex > stride ? nextIndex - stride : 0))) {
                //bound表示此分片的截止(最小)下标
                bound = nextBound;
                //i表示此分片的最大下标
                i = nextIndex - 1;
                //advance意为前进，跳出内层循环
                advance = false;
            }
        }
        if (i < 0 || i >= n || i + n >= nextn) {
            //进入到这里就意味着所有的桶位都已被处理完毕或是被包含在某个转移线程的申请分片中(即待转移)
            int sc;
            if (finishing) {
                //进行收尾工作，此工作一定是由最后一个分片申请线程进行的，这里用volatile写将nextTable置为null
                //，table指向新数组
                nextTable = null;
                table = nextTab;
                //sizeCtl设为新数组大小的3 / 4
                sizeCtl = (n << 1) - (n >>> 1);
                return;
            }
            //转移线程开始转移之前会将sizeCtl自增，转移完成之后自减，所以判断转移是否已经完成的方式便是sizeCtl是			  //否等于初始值
            if (U.compareAndSwapInt(this, SIZECTL, sc = sizeCtl, sc - 1)) {
                if ((sc - 2) != resizeStamp(n) << RESIZE_STAMP_SHIFT)
                    //还有其它线程尚未转移完成，直接退出，将收尾工作交给最后完成的那个线程
                    return;
                //进行到这里就说明当前线程为最后一个完成的线程，有意思的是这里又将advance置为true且i置为n(原)
                //数组的大小，作用就是最后再全部扫描一遍所有的桶位，看是否还有漏网之鱼
                finishing = advance = true;
                i = n;
            }
        }
        else if ((f = tabAt(tab, i)) == null)
            //2.
            advance = casTabAt(tab, i, null, fwd);
        else if ((fh = f.hash) == MOVED)
            advance = true; // already processed
        else {
            synchronized (f) {
                if (tabAt(tab, i) == f) {
                    Node<K,V> ln, hn;
                    if (fh >= 0) {
                        int runBit = fh & n;
                        Node<K,V> lastRun = f;
                        for (Node<K,V> p = f.next; p != null; p = p.next) {
                            int b = p.hash & n;
                            if (b != runBit) {
                                runBit = b;
                                lastRun = p;
                            }
                        }
                        if (runBit == 0) {
                            ln = lastRun;
                            hn = null;
                        }
                        else {
                            hn = lastRun;
                            ln = null;
                        }
                        for (Node<K,V> p = f; p != lastRun; p = p.next) {
                            int ph = p.hash; K pk = p.key; V pv = p.val;
                            if ((ph & n) == 0)
                                ln = new Node<K,V>(ph, pk, pv, ln);
                            else
                                hn = new Node<K,V>(ph, pk, pv, hn);
                        }
                        setTabAt(nextTab, i, ln);
                        setTabAt(nextTab, i + n, hn);
                        setTabAt(tab, i, fwd);
                        advance = true;
                    }
                    else if (f instanceof TreeBin) {
                        TreeBin<K,V> t = (TreeBin<K,V>)f;
                        TreeNode<K,V> lo = null, loTail = null;
                        TreeNode<K,V> hi = null, hiTail = null;
                        int lc = 0, hc = 0;
                        for (Node<K,V> e = t.first; e != null; e = e.next) {
                            int h = e.hash;
                            TreeNode<K,V> p = new TreeNode<K,V>
                                (h, e.key, e.val, null, null);
                            if ((h & n) == 0) {
                                if ((p.prev = loTail) == null)
                                    lo = p;
                                else
                                    loTail.next = p;
                                loTail = p;
                                ++lc;
                            }
                            else {
                                if ((p.prev = hiTail) == null)
                                    hi = p;
                                else
                                    hiTail.next = p;
                                hiTail = p;
                                ++hc;
                            }
                        }
                        ln = (lc <= UNTREEIFY_THRESHOLD) ? untreeify(lo) :
                            (hc != 0) ? new TreeBin<K,V>(lo) : t;
                        hn = (hc <= UNTREEIFY_THRESHOLD) ? untreeify(hi) :
                            (lc != 0) ? new TreeBin<K,V>(hi) : t;
                        setTabAt(nextTab, i, ln);
                        setTabAt(nextTab, i + n, hn);
                        setTabAt(tab, i, fwd);
                        advance = true;
                    }
                }
            }
        }
    }
}
```

##### 分片

每个线程针对一个分片来进行转移操作，所谓的一个分片其实就是bin数组的一段。默认的最小分片大小为16，如果所在机器 只有一个CPU核心，那么就取16，否则取(数组大小 / 8 / CPU核心数)与16的较大者。

##### transferIndex

全局变量transferIndex表示低于此值的bin尚未被转移，分片的申请便是通过对此变量的CAS操作来完成，初始值为原数组大小，减为0表示 所有桶位均已转移完毕。

##### ForwardingNode

从transfer方法的源码可以看出，当一个桶位(原数组)处理完时，会将其头结点设置一个ForwardingNode。简略版源码:

```java
static final class ForwardingNode<K,V> extends Node<K,V> {
    final Node<K,V>[] nextTable;
    ForwardingNode(Node<K,V>[] tab) {
        super(MOVED, null, null, null);
        this.nextTable = tab;
    }
}
```

其哈希值为MOVED。

