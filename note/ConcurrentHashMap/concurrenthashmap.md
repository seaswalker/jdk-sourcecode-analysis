我们对JDK1.8版本的ConcurrentHashMap进行说明，1.8版本的ConcurrentHashMap相比之前的版本主要做了两处改进:

- 使用CAS代替分段锁。
- 红黑树，这一点和HashMap是一致的。

