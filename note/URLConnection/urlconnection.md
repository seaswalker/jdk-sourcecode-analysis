类图:

![URLConnection类图](images/URLConnection.jpg)

我们以urlconnection包的简单对百度首页的读取来开启我们的源码阅读旅程。

# 连接建立

相关源码:

```java
//http://www.baidu.com
URL realUrl = new URL(urlName);
URLConnection conn = realUrl.openConnection();
conn.setRequestProperty("accept", "*/*");
conn.setRequestProperty("connection", "Keep-Alive");
conn.setRequestProperty("user-agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1; SV1)");
// 建立实际的连接
conn.connect();
```

## URL构建

URL负责协议的解析以及相应的协议处理器的创建，协议解析的过程其实就是一个字符串的处理过程。协议处理器即URLStreamHandler，类图:

![URLStreamHandler类图](images/URLStreamHandler.jpg)

其子类如下图所示:

![URLStreamHandler子类](images/URLStreamHandler子类.png)

每种协议的处理器便在sun.net.www.protocol.xxx中，URL获取处理器就是一个手动拼接类名，用反射生成实例的过程。