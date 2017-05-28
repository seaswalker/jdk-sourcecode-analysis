异步的文件通道，FileChannel属于"同步非阻塞"，AsynchronousFileChannel才是真正的异步。

![AsynchronousFileChannel](images/AsynchronousFileChannel.jpg)

从主要方法read/write的返回值为Future可以看出其"异步"的端倪。

# open

```java
public static AsynchronousFileChannel open(Path file,
                                           Set<? extends OpenOption> options,
                                           ExecutorService executor,
                                           FileAttribute<?>... attrs) {
    FileSystemProvider provider = file.getFileSystem().provider();
    return provider.newAsynchronousFileChannel(file, options, executor, attrs);
}
```

另外一个重载的简化方法声明如下:

```java
public static AsynchronousFileChannel open(Path file, OpenOption... options) {}
```

所做的处理便是将可变参数options手动转为Set，executor为null，文件属性为NO_ATTRIBUTES，其实就是一个空的数组。