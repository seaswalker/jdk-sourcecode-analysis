# ****创建

我们以下列代码为例:

```java
public static ExecutorService newFixedThreadPool(int nThreads) {
    return new ThreadPoolExecutor(nThreads, nThreads,
        0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<Runnable>());
}
```

可见默认使用LinkedBlockingQueue作为工作队列，其构造器:

```java
public LinkedBlockingQueue() {
    this(Integer.MAX_VALUE);
}
```

可见，这其实是一个有界队列，虽然大小为int最大值。

ThreadPoolExecutor便是JDK线程池的核心了，类图:

![ThreadPoolExecutor](images/ThreadPoolExecutor.jpg)

ThreadPoolExecutor构造器:

```java
public ThreadPoolExecutor(int corePoolSize,
                          int maximumPoolSize,
                          long keepAliveTime,
                          TimeUnit unit,
                          BlockingQueue<Runnable> workQueue) {
    this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
         Executors.defaultThreadFactory(), defaultHandler);
}
```

## 线程工厂

![ThreadFactory](images/ThreadFactory.jpg)

默认的线程工厂是Executors的内部类，核心的newThread方法:

```java
public Thread newThread(Runnable r) {
    Thread t = new Thread(group, r, namePrefix + threadNumber.getAndIncrement(), 0);
    if (t.isDaemon())
        t.setDaemon(false);
    if (t.getPriority() != Thread.NORM_PRIORITY)
        t.setPriority(Thread.NORM_PRIORITY);
    return t;
}
```

namePrefix定义:

```java
namePrefix = "pool-" + poolNumber.getAndIncrement() + "-thread-";
```

这便是为线程池默认创建的线程起名的地方了，Thread构造器的最后一个0为stackSize，0表示忽略此参数。

## 拒绝策略

从上面可以看出，线程池默认使用有界队列，所以当队列满的时候就需要考虑如何处理这种情况。

![RejectedExecutionHandler](images/RejectedExecutionHandler.jpg)

线程池默认采用的是:

```java
private static final RejectedExecutionHandler defaultHandler = new AbortPolicy();
```

即抛出异常，线程池退出:

```java
public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
    throw new RejectedExecutionException("Task " + r.toString() + " rejected from " + e.toString());
}
```

# execute

```java
public void execute(Runnable command) {
    if (command == null)
        throw new NullPointerException();
    int c = ctl.get();
    //corePoolSize为volatile，下面会提到为什么
    if (workerCountOf(c) < corePoolSize) {
        if (addWorker(command, true))
            //创建新线程成功，交由其执行
            return;
        c = ctl.get();
    }
    if (isRunning(c) && workQueue.offer(command)) {
        int recheck = ctl.get();
        if (! isRunning(recheck) && remove(command))
            reject(command);
        else if (workerCountOf(recheck) == 0)
            addWorker(null, false);
    }
    else if (!addWorker(command, false))
        reject(command);
}
```

## 控制变量

ctl是线程池的核心控制变量:

```java
private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
```

有以下两个用途:

- 高3位标志线程池的运行状态，比如运行、关闭。
- 低29位存储当前工作线程的个数，所以**一个线程池最多可以创建2 ^ 29 - 1(约为5亿)个线程**。

## 线程创建

当我们调用execute方法时，线程池将首先检查当前线程数是否已达到上限，如果没有创建新的工作线程，而不是入队。

```java
private boolean addWorker(Runnable firstTask, boolean core) {
    retry:
    for (;;) {
        int c = ctl.get();
        int rs = runStateOf(c);
        // 检查线程池状态，如果已关闭，返回false
        if (rs >= SHUTDOWN && ! (rs == SHUTDOWN && firstTask == null && ! workQueue.isEmpty()))
            return false;
        for (;;) {
            int wc = workerCountOf(c);
            //检查是否达到上限
            if (wc >= CAPACITY || wc >= (core ? corePoolSize : maximumPoolSize))
                return false;
            //如果CAS增加线程数成功，中断循环 ，进行线程创建
            if (compareAndIncrementWorkerCount(c))
                break retry;
            c = ctl.get();  // Re-read ctl
            if (runStateOf(c) != rs)
                continue retry;
            // else CAS failed due to workerCount change; retry inner loop
        }
    }

    boolean workerStarted = false;
    boolean workerAdded = false;
    Worker w = null;
    try {
        w = new Worker(firstTask);
        final Thread t = w.thread;
        if (t != null) {
            final ReentrantLock mainLock = this.mainLock;
            //shutdown等方法也需要加锁，所以可以保证线程安全
            mainLock.lock();
            try {
                //再次检查状态
                int rs = runStateOf(ctl.get());
                if (rs < SHUTDOWN || (rs == SHUTDOWN && firstTask == null)) {
                    if (t.isAlive()) // precheck that t is startable
                        throw new IllegalThreadStateException();
                    //workers是一个HashSet
                    workers.add(w);
                    int s = workers.size();
                    //用于记录出现过的最大线程数
                    if (s > largestPoolSize)
                        largestPoolSize = s;
                    workerAdded = true;
                }
            } finally {
                mainLock.unlock();
            }
            if (workerAdded) {
                t.start();
                workerStarted = true;
            }
        }
    } finally {
        if (! workerStarted)
            addWorkerFailed(w);
    }
    return workerStarted;
}
```

### Worker

这里的"线程(即Worker)"其实是ThreadPoolExecutor的内部类。

![Worker](images/Worker.jpg)

又见AQS。构造器:

```java
Worker(Runnable firstTask) {
    setState(-1); // inhibit interrupts until runWorker
    this.firstTask = firstTask;
    this.thread = getThreadFactory().newThread(this);
}
```

其run方法的真正逻辑由ThreadPoolExecutor.runWorker实现:

```java
final void runWorker(Worker w) {
    Thread wt = Thread.currentThread();
    Runnable task = w.firstTask;
    w.firstTask = null;
    boolean completedAbruptly = true;
    try {
        while (task != null || (task = getTask()) != null) {
            w.lock();
            //中断状态
            if ((runStateAtLeast(ctl.get(), STOP) || (Thread.interrupted() &&
                 runStateAtLeast(ctl.get(), STOP))) && !wt.isInterrupted())
                wt.interrupt();
            try {
                task.run();
            } finally {
                task = null;
                w.completedTasks++;
                w.unlock();
            }
        }
        completedAbruptly = false;
    } finally {
        processWorkerExit(w, completedAbruptly);
    }
}
```

#### 锁

可以看出，一次任务的执行是在所在Worker的锁的保护下进行的，结合后面shutdownNow的源码可以发现，shutdownNow中断Worker的前提是获得锁，这就很好的体现了shutdownNow的语义: 阻止新任务的提交，**等待**所有已有任务执行完毕。

#### 中断状态

这里有两种意义 :

- 如果线程池处于STOP(或之后)的状态，即shutdownNow方法已被调用，那么此处代码将确保线程的中断标志位一定被设置。
- 如果线程池处于STOP之前的状态，比如SHUTDOWN或RUNNING，那么Worker不应响应中断，即应当清除中断标志，但是暂时没有想到谁会设置Worker线程的中断标志位，难道是我们的业务代码?

在这里扒一扒到底什么是线程中断:

```java
public void interrupt() {
    synchronized (blockerLock) {
        Interruptible b = blocker;
        if (b != null) {
            interrupt0();           // Just to set the interrupt flag
            b.interrupt(this);
            return;
        }
    }
    interrupt0();
}
```

blocker在nio部分已经见过了，interrupt0的最终native实现位于openjdk\hotspot\src\os\solaris\vm\os_solaris.cpp(Linux):

```c++
void os::interrupt(Thread* thread) {
  OSThread* osthread = thread->osthread();
  int isInterrupted = osthread->interrupted();
  if (!isInterrupted) {
      //设置标志位
      osthread->set_interrupted(true);
      OrderAccess::fence();
      //唤醒sleep()?
      ParkEvent * const slp = thread->_SleepEvent ;
      if (slp != NULL) slp->unpark() ;
  }
  //唤醒LockSupport.park()?
  if (thread->is_Java_thread()) {
    ((JavaThread*)thread)->parker()->unpark();
  }
  //唤醒Object.wait()?
  ParkEvent * const ev = thread->_ParkEvent ;
  if (ev != NULL) ev->unpark() ;
  // When events are used everywhere for os::sleep, then this thr_kill
  // will only be needed if UseVMInterruptibleIO is true.
  if (!isInterrupted) {
    int status = thr_kill(osthread->thread_id(), os::Solaris::SIGinterrupt());
    assert_status(status == 0, status, "thr_kill");
    // Bump thread interruption counter
    RuntimeService::record_thread_interrupt_signaled_count();
  }
}
```

与java里已知的可被中断的阻塞大体可以找到对应关系。



