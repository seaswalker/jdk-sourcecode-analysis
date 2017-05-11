![ThreadLocal](images/ThreadLocal.jpg)

# get

```java
public T get() {
    Thread t = Thread.currentThread();
    ThreadLocalMap map = getMap(t);
    if (map != null) {
        ThreadLocalMap.Entry e = map.getEntry(this);
        if (e != null) {
            @SuppressWarnings("unchecked")
            T result = (T)e.value;
            return result;
        }
    }
    return setInitialValue();
}
```

getMap实现:

```java
ThreadLocalMap getMap(Thread t) {
    return t.threadLocals;
}
```

可以看出，其实ThreadLocalMap作为线程Thread的属性而存在:

```java
ThreadLocal.ThreadLocalMap threadLocals = null;
```

##  Map创建

先来看一下线程的ThreadLocalMap属性不存在的情况，setInitialValue方法:

```java
private T setInitialValue() {
    T value = initialValue();
    Thread t = Thread.currentThread();
    ThreadLocalMap map = getMap(t);
    if (map != null)
        map.set(this, value);
    else
        createMap(t, value);
    return value;
}
```

initialValue方法便是我们第一次访问用以获得初始化值的方法:

```java
protected T initialValue() {
    return null;
}
```

所以，这便解释了我们在使用ThreadLocal时为什么要创建一个ThreadLocal的子类并覆盖此方法。

```java
void createMap(Thread t, T firstValue) {
    t.threadLocals = new ThreadLocalMap(this, firstValue);
}
```

构造参数为初始增加的一个键值对，从这里可以看出，**ThreadLocalMap以ThreadLocal对象为键**。

### ThreadLocalMap

其声明如下:

```java
static class ThreadLocalMap {}
```

那么问题来了，这里为什么要重新实现一个Map，而不用已有的HashMap等类呢?基于以下几点考虑:

- 所有方法均为private。
- 内部类Entry继承自WeakReference，当内存紧张时可以对ThreadLocal变量进行回收，注意这里并没有结合ReferenceQueue使用。

构造器源码:

```java
ThreadLocalMap(ThreadLocal<?> firstKey, Object firstValue) {
    table = new Entry[INITIAL_CAPACITY];
    int i = firstKey.threadLocalHashCode & (INITIAL_CAPACITY - 1);
    table[i] = new Entry(firstKey, firstValue);
    size = 1;
    setThreshold(INITIAL_CAPACITY);
}
```

setThreshold:

```java
private void setThreshold(int len) {
    threshold = len * 2 / 3;
}
```

和HashMap的套路一样，只不过这里负载因子写死了，2 / 3，强调一下，**不是3 / 4 !!!**

# set

```java
public void set(T value) {
    Thread t = Thread.currentThread();
    ThreadLocalMap map = getMap(t);
    if (map != null)
        map.set(this, value);
    else
        createMap(t, value);
}
```

正如注释中所说，我们在使用ThreadLocal时应该去覆盖initialValue方法，而不是set。显然这里的核心便是ThreadLocalMap的set方法:

```java
private void set(ThreadLocal<?> key, Object value) {
    Entry[] tab = table;
    int len = tab.length;
    int i = key.threadLocalHashCode & (len-1);
    for (Entry e = tab[i]; e != null; e = tab[i = nextIndex(i, len)]) {
        ThreadLocal<?> k = e.get();
        //bin里的第一个节点即为所需key，更新value
        if (k == key) {
            e.value = value;
            return;
        }
        if (k == null) {
            replaceStaleEntry(key, value, i);
            return;
        }
    }
    tab[i] = new Entry(key, value);
    int sz = ++size;
    if (!cleanSomeSlots(i, sz) && sz >= threshold)
        rehash();
}
```

