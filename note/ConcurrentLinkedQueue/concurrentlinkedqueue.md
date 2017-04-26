![ConcurrentLinkedQueue](images/ConcurrentLinkedQueue.jpg)

内部类Node的类图如下:

![Node类图](images/Node.jpg)

# 算法

此类的实现基于算法Michael & Scott algorithm，论文:

[Simple, Fast, and Practical Non-Blocking and Blocking Concurrent Queue Algorithms](https://www.research.ibm.com/people/m/michael/podc-1996.pdf)

# offer

```java
public boolean offer(E e) {
    checkNotNull(e);
    final Node<E> newNode = new Node<E>(e);

    for (Node<E> t = tail, p = t;;) {
        Node<E> q = p.next;
        if (q == null) {
            // p is last node
            if (p.casNext(null, newNode)) {
                if (p != t) // hop two nodes at a time
                    casTail(t, newNode);  // Failure is OK.
                return true;
            }
            // Lost CAS race to another thread; re-read next
        }
        else if (p == q)
            // We have fallen off list.  If tail is unchanged, it
            // will also be off-list, in which case we need to
            // jump to head, from which all live nodes are always
            // reachable.  Else the new tail is a better bet.
            p = (t != (t = tail)) ? t : head;
        else
            // Check for tail updates after two hops.
            p = (p != t && t != (t = tail)) ? t : q;
    }
}
```

注意，由于ConcurrentLinkedQueue为无界队列，所以offer方法永远返回true。