# 创建

我们一般使用下面的方式进行创建:

```java
public static ScheduledExecutorService newSingleThreadScheduledExecutor() {
    return new DelegatedScheduledExecutorService(new ScheduledThreadPoolExecutor(1));
}
```

DelegatedScheduledExecutorService实际上是对ScheduledExecutorService接口方法的转发，目的是只将ScheduledExecutorService接口的public方法暴露出来，这其实就是门面模式。

显然这里的核心便是ScheduledThreadPoolExecutor了:

![ScheduledThreadPoolExecutor](images/ScheduledThreadPoolExecutor.jpg)

ScheduledThreadPoolExecutor构造器:

```java
public ScheduledThreadPoolExecutor(int corePoolSize) {
    super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS, new DelayedWorkQueue());
}
```

所以，默认情况下创建的是corePoolSize为1的线程池，而maximumPoolSize却为int最大值!

其工作队列DelayedWorkQueue是ScheduledThreadPoolExecutor的嵌套类:

![DelayedWorkQueue](images/DelayedWorkQueue.jpg)

# 单次调度

```java
public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
    RunnableScheduledFuture<V> t = decorateTask(callable,
        new ScheduledFutureTask<V>(callable, triggerTime(delay, unit)));
    delayedExecute(t);
    return t;
}
```

## 触发时间计算

```java
private long triggerTime(long delay, TimeUnit unit) {
    return triggerTime(unit.toNanos((delay < 0) ? 0 : delay));
}
long triggerTime(long delay) {
     return now() + ((delay < (Long.MAX_VALUE >> 1)) ? delay : overflowFree(delay));
}
```

now方法即返回当前纳秒表示的时间，所以触发时间就是当前时间加延时。

## 任务包装

Callable任务被包装成了ScheduledFutureTask对象，其是ScheduledThreadPoolExecutor的内部类:

![ScheduledFutureTask](images/ScheduledFutureTask.jpg)

decorateTask是一个模板方法，空实现。

## 调度

核心便是delayedExecute方法:

```java
private void delayedExecute(RunnableScheduledFuture<?> task) {
    if (isShutdown())
        reject(task);
    else {
        super.getQueue().add(task);
        if (isShutdown() &&
            !canRunInCurrentRunState(task.isPeriodic()) &&
            remove(task))
            task.cancel(false);
        else
            ensurePrestart();
    }
}
```

isShutdown方法在父类ThreadPoolExecutor中实现，利用的便是我们已经提到过的状态记录的方法。

### 工作队列

DelayedWorkQueue的类图位于上面创建一节中，其实此队列便是调度实现的核心，此队列实际上用数组实现了一个小顶堆，其add方法实际上通过offer方法实现:

```java
public boolean offer(Runnable x) {
    RunnableScheduledFuture<?> e = (RunnableScheduledFuture<?>)x;
    final ReentrantLock lock = this.lock;
    lock.lock();
    try {
        int i = size;
        //扩容
        if (i >= queue.length)
            grow();
        size = i + 1;
        //队列为empty
        if (i == 0) {
            queue[0] = e;
            setIndex(e, 0);
        } else {
            siftUp(i, e);
        }
        if (queue[0] == e) {
            leader = null;
            available.signal();
        }
    } finally {
        lock.unlock();
    }
    return true;
}
```

queue便是用以实现小顶堆的数组:

```java
private RunnableScheduledFuture<?>[] queue = new RunnableScheduledFuture<?>[INITIAL_CAPACITY];
```

初始大小为16.

堆首先是一棵完全二叉树，按照如下的顺序将其节点存储到数组中:

![堆节点顺序](images/heap.png)

满足以下的性质:

- 任一节点的父节点的数组下标为i / 2.
- 节点的左子节点的下标为i * 2, 右子节点的下标为i * 2 + 1.
- 添加节点时将节点放在数组的最后一个位置，然后不断的将此节点的值与其父节点比较，如果不满足堆的条件，交换之.
- 堆排序的时间复杂度: O(NlongN).

#### 扩容

```java
private void grow() {
    int oldCapacity = queue.length;
    int newCapacity = oldCapacity + (oldCapacity >> 1); // grow 50%
    if (newCapacity < 0) // overflow
        newCapacity = Integer.MAX_VALUE;
    queue = Arrays.copyOf(queue, newCapacity);
}
```

1.5倍扩容，最大取int最大值。

#### 上移

即使堆再次平衡的过程:

```java
private void siftUp(int k, RunnableScheduledFuture<?> key) {
    while (k > 0) {
        int parent = (k - 1) >>> 1;
        RunnableScheduledFuture<?> e = queue[parent];
        if (key.compareTo(e) >= 0)
            break;
        queue[k] = e;
        setIndex(e, k);
        k = parent;
    }
    queue[k] = key;
    setIndex(key, k);
}
```

很容易理解，就是一个和父节点交换，直到父节点的延时时间小于当前任务。

### 状态检查

将任务提交到队列后会再次对线程池当前的状态进行检查，相关源码:

```java
super.getQueue().add(task);
if (isShutdown() && !canRunInCurrentRunState(task.isPeriodic()) && remove(task)) {
    task.cancel(false);
}
```

isShutdown成立的条件是当前状态不是运行状态，isPeriodic方法用以判断任务是否是持续任务:

```java
public boolean isPeriodic() {
    return period != 0;
}
```

period是ScheduledFutureTask的属性，其不同的取值意义如下:

- 正值: 按固定的时间间隔调度
- 负值: 按固定的时间延迟进行调度
- 零: 单次任务

canRunInCurrentRunState:

```java
boolean canRunInCurrentRunState(boolean periodic) {
    return isRunningOrShutdown(periodic ?
                                continueExistingPeriodicTasksAfterShutdown :
                                executeExistingDelayedTasksAfterShutdown);
}
```

ThreadPoolExecutor.isRunningOrShutdown:

```java
final boolean isRunningOrShutdown(boolean shutdownOK) {
    int rs = runStateOf(ctl.get());
    return rs == RUNNING || (rs == SHUTDOWN && shutdownOK);
}
```

这里判断的是在当前的状态下是否可以执行任务，SHUTDOWN态是由于shutdown方法被调用所致，不是shutdownNow。

continueExistingPeriodicTasksAfterShutdown和executeExistingDelayedTasksAfterShutdown属性ScheduledThreadPoolExecutor为我们留下了setter方法，你懂的。

### 任务移除

如果当前已不能进行任务执行，那么便将刚提交的任务从堆中移除，核心的实现为DelayedWorkQueue的同名方法:

```java
public boolean remove(Object x) {
    final ReentrantLock lock = this.lock;
    lock.lock();
    try {
        int i = indexOf(x);
        if (i < 0)
            return false;
        setIndex(queue[i], -1);
        int s = --size;
        RunnableScheduledFuture<?> replacement = queue[s];
        queue[s] = null;
        if (s != i) {
            siftDown(i, replacement);
            if (queue[i] == replacement)
                //不能进行下移，再试试上移?
                siftUp(i, replacement);
        }
        return true;
    } finally {
        lock.unlock();
    }
}
```

关键在于条件判断`if (s != i)`，即被移除的节点不是最后(数组的最后)一个节点，在这种情况下会导致数组i处出现一个空位，所以在这里进行了先下移再上移的尝试，以使用最末节点或其它节点填补此空位，同时数组大小减一。

### 任务取消

ScheduledFutureTask.cancel:

```java
public boolean cancel(boolean mayInterruptIfRunning) {
    boolean cancelled = super.cancel(mayInterruptIfRunning);
    if (cancelled && removeOnCancel && heapIndex >= 0)
        remove(this);
    return cancelled;
}
```

父类FutureTask的cancel方法已经见过了，removeOnCancel为ScheduledThreadPoolExecutor的属性，默认为false，其实这里调用remove是不必要的，因为已经被调用过了。

### Worker启动

ThreadPoolExecutor.ensurePrestart:

```java
void ensurePrestart() {
    int wc = workerCountOf(ctl.get());
    if (wc < corePoolSize)
        addWorker(null, true);
    else if (wc == 0)
        addWorker(null, false);
}
```

即使corePoolSize为0，也要保证有一个Worker线程。

# 任务获取

在ThreadPoolExecutor我们已经见过了，Worker线程通过调用任务队列的take方法进行获取:

```java
public RunnableScheduledFuture<?> take() throws InterruptedException {
    final ReentrantLock lock = this.lock;
    lock.lockInterruptibly();
    try {
        for (;;) {
            RunnableScheduledFuture<?> first = queue[0];
            //堆为空
            if (first == null)
                available.await();
            else {
                long delay = first.getDelay(NANOSECONDS);
                //getDelay返回的是延时执行时间和当前时间的差，非正值说明此任务可以执行了
                if (delay <= 0)
                    return finishPoll(first);
                first = null;
                if (leader != null)
                    //已存在leader，所以当前线程为follower，永久等待
                    available.await();
                else {
                    Thread thisThread = Thread.currentThread();
                    leader = thisThread;
                    try {
                        //当前线程成为leader，等待至下一次任务执行时间
                        available.awaitNanos(delay);
                    } finally {
                        if (leader == thisThread)
                            leader = null;
                    }
                }
            }
        }
    } finally {
        if (leader == null && queue[0] != null)
            //当前线程接下来要去执行定时任务逻辑，所以唤醒一个follower(如果有)，使之成为新的leader
            available.signal();
        lock.unlock();
    }
}
```

这里其实应用了Leader/Follower模式，参考:

[Leader/Follower多线程网络模型介绍 ](http://blog.csdn.net/goldlevi/article/details/7705180)

使用这种模式的原因猜想应该是这样: 由于定时任务的特殊性，在某一时刻应该只有一个任务等开始时间最短，这样的话只让一个线程阻塞至既定时间即可，其它线程及时醒来也不能立即执行任务，从而造成了性能的浪费。

如果堆为空，那么等待的Worker何时被唤醒呢?玄机就在offer方法，相关源码:

```java
if (queue[0] == e) {
    leader = null;
    available.signal();
}
```

为什么新任务被至于堆顶时需要唤醒Worker呢，因为这就意味着之前堆为空或最近需要执行任务的时间已经改变，需要重新调整leader的睡眠时间。

finishPoll方法很容易猜到，就是填补堆顶的空缺:

```java
private RunnableScheduledFuture<?> finishPoll(RunnableScheduledFuture<?> f) {
    int s = --size;
    RunnableScheduledFuture<?> x = queue[s];
    queue[s] = null;
    if (s != 0)
        siftDown(0, x);
    setIndex(f, -1);
    return f;
}
```

将最后 一个元素从堆顶使其"沉沦"。

# 重生

对于持续执行的任务，在一次执行完成后应该将其再次放入到堆中，以待下次执行，这一步是在ScheduledFutureTask的run方法中完成:

```java
public void run() {
    boolean periodic = isPeriodic();
    if (!canRunInCurrentRunState(periodic))
        cancel(false);
    //单次任务
    else if (!periodic)
        ScheduledFutureTask.super.run();
    //持续任务
    else if (ScheduledFutureTask.super.runAndReset()) {
        //设置下次执行的时间
        setNextRunTime();
        //重新加入到堆中
        reExecutePeriodic(outerTask);
    }
}
```

FutureTask.runAndReset方法便是调用任务逻辑的地方，不同于我们已经见过的run方法，这里**不会设置任务执行的结果(即outcome属性)，也不会改变Future的状态**，所以即使一次执行完毕，Future看到的状态仍是未完成。

# shutdown

主要逻辑由父类ThreadPoolExecutor实现，唯一的区别便在于ScheduledThreadPoolExecutor实现了父类的模板方法onShutdown(简略版源码):

```java
@Override void onShutdown() {
    BlockingQueue<Runnable> q = super.getQueue();
    boolean keepDelayed = getExecuteExistingDelayedTasksAfterShutdownPolicy();
    boolean keepPeriodic = getContinueExistingPeriodicTasksAfterShutdownPolicy();
    if (!keepDelayed && !keepPeriodic) {
        for (Object e : q.toArray())
            if (e instanceof RunnableScheduledFuture<?>)
                ((RunnableScheduledFuture<?>) e).cancel(false);
        q.clear();
    }
}
```

这里所做的就是将堆中所有未执行的任务取消，所以如果有线程阻塞在等待任务的结果上最终可以返回。

# shutdownNow

直接调用父类的方法实现，可以想象，这便会导致线程池已关闭但`Future.get`无法返回。







