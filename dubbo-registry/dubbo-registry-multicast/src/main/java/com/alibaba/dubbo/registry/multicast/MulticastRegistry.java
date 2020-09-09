/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.registry.multicast;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ExecutorUtil;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.support.FailbackRegistry;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * MulticastRegistry
 *
 * 该类就是针对注册中心核心的功能注册、订阅、取消注册、取消订阅，查询注册列表进行展开，利用广播的方式去实现
 *
 * mutilcastSocket，该类是muticast注册中心实现的关键，这里补充一下单播、广播、以及多播的区别，因为下面会涉及到。
 * 单播是每次只有两个实体相互通信，发送端和接收端都是唯一确定的；
 * 广播目的地址为网络中的全体目标，而多播的目的地址是一组目标，加入该组的成员均是数据包的目的地。
 *
 * 关注任务调度器和清理计时器，该类封装了定时清理过期的服务的策略。
 */
public class MulticastRegistry extends FailbackRegistry {

    // logging output
    private static final Logger logger = LoggerFactory.getLogger(MulticastRegistry.class);
    // 默认的多点广播端口
    private static final int DEFAULT_MULTICAST_PORT = 1234;
    // 多点广播的地址
    private final InetAddress multicastAddress;
    // 多点广播
    private final MulticastSocket multicastSocket;
    // 多点广播端口
    private final int multicastPort;
    //收到的URL
    private final ConcurrentMap<URL, Set<URL>> received = new ConcurrentHashMap<URL, Set<URL>>();
    // 任务调度器
    private final ScheduledExecutorService cleanExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("DubboMulticastRegistryCleanTimer", true));
    // 定时清理执行器，一定时间清理过期的url
    private final ScheduledFuture<?> cleanFuture;
    // 清理的间隔时间
    private final int cleanPeriod;
    // 管理员权限
    private volatile boolean admin = false;

    /**
     * 线程中做的工作是根据接收到的消息来判定是什么请求，作出对应的操作，只要mutilcastSocket没有断开，就一直接收消息，内部的实现体现在receive方法中，下文会展开讲述。
     * 定时清理任务是清理过期的注册的服务。通过两次socket的尝试来判定是否过期。clean方法下文会展开讲述
     * @param url
     */
    public MulticastRegistry(URL url) {
        super(url);
        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }
        try {
            multicastAddress = InetAddress.getByName(url.getHost());
            checkMulticastAddress(multicastAddress);
            // 如果url携带的配置中没有端口号，则使用默认端口号
            multicastPort = url.getPort() <= 0 ? DEFAULT_MULTICAST_PORT : url.getPort();
            multicastSocket = new MulticastSocket(multicastPort);
            // 加入同一组广播
            NetUtils.joinMulticastGroup(multicastSocket, multicastAddress);
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buf = new byte[2048];
                    // 实例化数据报
                    DatagramPacket recv = new DatagramPacket(buf, buf.length);
                    while (!multicastSocket.isClosed()) {
                        try {
                            // 接收数据包
                            multicastSocket.receive(recv);
                            String msg = new String(recv.getData()).trim();
                            int i = msg.indexOf('\n');
                            if (i > 0) {
                                msg = msg.substring(0, i).trim();
                            }
                            // 接收消息请求，根据消息并相应操作，比如注册，订阅等
                            MulticastRegistry.this.receive(msg, (InetSocketAddress) recv.getSocketAddress());
                            Arrays.fill(buf, (byte) 0);
                        } catch (Throwable e) {
                            if (!multicastSocket.isClosed()) {
                                logger.error(e.getMessage(), e);
                            }
                        }
                    }
                }
            }, "DubboMulticastRegistryReceiver");
            thread.setDaemon(true); // 设置为守护进程
            thread.start();  // 开启线程
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        // 优先从url中获取清理延迟配置，若没有，则默认为60s
        this.cleanPeriod = url.getParameter(Constants.SESSION_TIMEOUT_KEY, Constants.DEFAULT_SESSION_TIMEOUT);
        // 如果配置了需要清理
        if (url.getParameter("clean", true)) {
            // 开启计时器
            this.cleanFuture = cleanExecutor.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        clean(); // Remove the expired // 清理过期的服务
                    } catch (Throwable t) { // Defensive fault tolerance
                        logger.error("Unexpected exception occur at clean expired provider, cause: " + t.getMessage(), t);
                    }
                }
            }, cleanPeriod, cleanPeriod, TimeUnit.MILLISECONDS);
        } else {
            this.cleanFuture = null;
        }
    }

    private void checkMulticastAddress(InetAddress multicastAddress) {
        if (!multicastAddress.isMulticastAddress()) {
            String message = "Invalid multicast address " + multicastAddress;
            if (!(multicastAddress instanceof Inet4Address)) {
                throw new IllegalArgumentException(message + ", " +
                        "ipv4 multicast address scope: 224.0.0.0 - 239.255.255.255.");
            } else {
                throw new IllegalArgumentException(message + ", " + "ipv6 multicast address must start with ff, " +
                        "for example: ff01::1");
            }
        }
    }

    /**
     * 判断是否为多点广播地址，地址范围是224.0.0.0至239.255.255.255。
     * @param ip
     * @return
     */
    private static boolean isMulticastAddress(String ip) {
        int i = ip.indexOf('.');
        if (i > 0) {
            String prefix = ip.substring(0, i);
            if (StringUtils.isInteger(prefix)) {
                int p = Integer.parseInt(prefix);
                return p >= 224 && p <= 239;
            }
        }
        return false;
    }

    /**
     * 关机的是如何判断过期以及做的取消注册的操作
     */
    private void clean() {
        // 当url中携带的服务接口配置为是*时候，才可以执行清理
        if (admin) {
            for (Set<URL> providers : new HashSet<Set<URL>>(received.values())) {
                for (URL url : new HashSet<URL>(providers)) {
                    if (isExpired(url)) {// 判断是否过期
                        if (logger.isWarnEnabled()) {
                            logger.warn("Clean expired provider " + url);
                        }
                        doUnregister(url);
                    }
                }
            }
        }
    }

    /**
     * 判断服务是否过期，有两次尝试socket的操作，如果尝试失败，则判断为过期。
     * @param url
     * @return
     */
    private boolean isExpired(URL url) {
        // 如果为非动态管理模式或者协议是consumer、route或者override，则没有过期
        if (!url.getParameter(Constants.DYNAMIC_KEY, true)
                || url.getPort() <= 0
                || Constants.CONSUMER_PROTOCOL.equals(url.getProtocol())
                || Constants.ROUTE_PROTOCOL.equals(url.getProtocol())
                || Constants.OVERRIDE_PROTOCOL.equals(url.getProtocol())) {
            return false;
        }
        Socket socket = null;
        try {
            // 利用url携带的主机地址和端口号实例化socket
            socket = new Socket(url.getHost(), url.getPort());
        } catch (Throwable e) {
            // 如果实例化失败，等待100ms重试第二次，如果还失败，则判定已过期
            try {
                Thread.sleep(100); // 等待100ms
            } catch (Throwable e2) {
            }
            Socket socket2 = null;
            try {
                socket2 = new Socket(url.getHost(), url.getPort());
            } catch (Throwable e2) {
                return true;
            } finally {
                if (socket2 != null) {
                    try {
                        socket2.close();
                    } catch (Throwable e2) {
                    }
                }
            }
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable e) {
                }
            }
        }
        return false;
    }

    /**
     * 据接收到的消息开头的数据来判断需要做什么类型的操作，重点在于订阅，可以选择单播订阅还是广播订阅，这个取决于url携带的配置是什么。
     * @param msg
     * @param remoteAddress
     */
    private void receive(String msg, InetSocketAddress remoteAddress) {
        if (logger.isInfoEnabled()) {
            logger.info("Receive multicast message: " + msg + " from " + remoteAddress);
        }
        // 如果这个消息是以register、unregister、subscribe开头的，则进行相应的操作
        if (msg.startsWith(Constants.REGISTER)) {
            URL url = URL.valueOf(msg.substring(Constants.REGISTER.length()).trim());
            registered(url);// 注册服务
        } else if (msg.startsWith(Constants.UNREGISTER)) {
            URL url = URL.valueOf(msg.substring(Constants.UNREGISTER.length()).trim());
            unregistered(url); // 取消注册服务
        } else if (msg.startsWith(Constants.SUBSCRIBE)) {
            URL url = URL.valueOf(msg.substring(Constants.SUBSCRIBE.length()).trim());
            // 获得以及注册的url集合
            Set<URL> urls = getRegistered();
            if (urls != null && !urls.isEmpty()) {
                for (URL u : urls) {
                    // 判断是否合法
                    if (UrlUtils.isMatch(url, u)) {
                        String host = remoteAddress != null && remoteAddress.getAddress() != null
                                ? remoteAddress.getAddress().getHostAddress() : url.getIp();
                        // 建议服务提供者和服务消费者在不同机器上运行，如果在同一机器上，需设置unicast=false
                        // 同一台机器中的多个进程不能单播单播，或者只有一个进程接收信息，发给消费者的单播消息可能被提供者抢占，两个消费者在同一台机器也一样，
                        // 只有multicast注册中心有此问题
                        if (url.getParameter("unicast", true) // Whether the consumer's machine has only one process
                                && !NetUtils.getLocalHost().equals(host)) { // Multiple processes in the same machine cannot be unicast with unicast or there will be only one process receiving information
                            unicast(Constants.REGISTER + " " + u.toFullString(), host);
                        } else {
                            broadcast(Constants.REGISTER + " " + u.toFullString());
                        }
                    }
                }
            }
        }/* else if (msg.startsWith(UNSUBSCRIBE)) {
        }*/
    }

    private void broadcast(String msg) {
        if (logger.isInfoEnabled()) {
            logger.info("Send broadcast message: " + msg + " to " + multicastAddress + ":" + multicastPort);
        }
        try {
            byte[] data = (msg + "\n").getBytes();
            // 实例化数据报,重点是目的地址是mutilcastAddress
            DatagramPacket hi = new DatagramPacket(data, data.length, multicastAddress, multicastPort);
            multicastSocket.send(hi);  // 发送数据报
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private void unicast(String msg, String host) {
        if (logger.isInfoEnabled()) {
            logger.info("Send unicast message: " + msg + " to " + host + ":" + multicastPort);
        }
        try {
            byte[] data = (msg + "\n").getBytes();
            // 实例化数据报,重点是目的地址是只是单个地址
            DatagramPacket hi = new DatagramPacket(data, data.length, InetAddress.getByName(host), multicastPort);
            multicastSocket.send(hi); // 发送数据报
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    protected void doRegister(URL url) {
        broadcast(Constants.REGISTER + " " + url.toFullString());
    }

    @Override
    protected void doUnregister(URL url) {
        broadcast(Constants.UNREGISTER + " " + url.toFullString());
    }

    @Override
    protected void doSubscribe(URL url, NotifyListener listener) {
        // 当url中携带的服务接口配置为是*时候，才可以执行清理，类似管理员权限
        if (Constants.ANY_VALUE.equals(url.getServiceInterface())) {
            admin = true;
        }
        broadcast(Constants.SUBSCRIBE + " " + url.toFullString());
        synchronized (listener) { // 对监听器进行同步锁
            try {
                listener.wait(url.getParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT));
            } catch (InterruptedException e) {
            }
        }
    }

    @Override
    protected void doUnsubscribe(URL url, NotifyListener listener) {
        if (!Constants.ANY_VALUE.equals(url.getServiceInterface())
                && url.getParameter(Constants.REGISTER_KEY, true)) {
            unregister(url);
        }
        broadcast(Constants.UNSUBSCRIBE + " " + url.toFullString());
    }

    @Override
    public boolean isAvailable() {
        try {
            return multicastSocket != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            if (cleanFuture != null) {// 取消清理任务
                cleanFuture.cancel(true);
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        try {
            // 把该地址从组内移除
            multicastSocket.leaveGroup(multicastAddress);
            multicastSocket.close();// 关闭mutilcastSocket
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        // 关闭线程池
        ExecutorUtil.gracefulShutdown(cleanExecutor, cleanPeriod);
    }

    protected void registered(URL url) {
        // 遍历订阅的监听器集合
        for (Map.Entry<URL, Set<NotifyListener>> entry : getSubscribed().entrySet()) {
            URL key = entry.getKey();
            if (UrlUtils.isMatch(key, url)) { // 判断是否合法
                // 通过消费者url获得接收到的服务url集合
                Set<URL> urls = received.get(key);
                if (urls == null) {
                    received.putIfAbsent(key, new ConcurrentHashSet<URL>());
                    urls = received.get(key);
                }
                urls.add(url); // 加入服务url
                List<URL> list = toList(urls);
                for (NotifyListener listener : entry.getValue()) {
                    notify(key, listener, list);// 把服务url的变化通知监听器
                    synchronized (listener) {
                        listener.notify();
                    }
                }
            }
        }
    }

    /**
     * 把需要取消注册的服务url从缓存中移除，然后如果没有接收的服务url了，就加入一个携带empty协议的url，然后通知监听器服务变化。
     * @param url
     */
    protected void unregistered(URL url) {
        // 遍历订阅的监听器集合
        for (Map.Entry<URL, Set<NotifyListener>> entry : getSubscribed().entrySet()) {
            URL key = entry.getKey();
            if (UrlUtils.isMatch(key, url)) {
                Set<URL> urls = received.get(key);
                if (urls != null) { // 缓存中移除
                    urls.remove(url);
                }
                if (urls == null || urls.isEmpty()){
                    if (urls == null){
                        urls = new ConcurrentHashSet<URL>();
                    }
                    // 设置携带empty协议的url
                    URL empty = url.setProtocol(Constants.EMPTY_PROTOCOL);
                    urls.add(empty);
                }
                List<URL> list = toList(urls);
                // 通知监听器 服务url变化
                for (NotifyListener listener : entry.getValue()) {
                    notify(key, listener, list);
                }
            }
        }
    }

    protected void subscribed(URL url, NotifyListener listener) {
        List<URL> urls = lookup(url);
        notify(url, listener, urls);
    }

    private List<URL> toList(Set<URL> urls) {
        List<URL> list = new ArrayList<URL>();
        if (urls != null && !urls.isEmpty()) {
            for (URL url : urls) {
                list.add(url);
            }
        }
        return list;
    }

    @Override
    public void register(URL url) {
        super.register(url);
        registered(url);
    }

    @Override
    public void unregister(URL url) {
        super.unregister(url);
        unregistered(url);
    }

    @Override
    public void subscribe(URL url, NotifyListener listener) {
        super.subscribe(url, listener);
        subscribed(url, listener);
    }

    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        super.unsubscribe(url, listener);
        received.remove(url);
    }

    @Override
    public List<URL> lookup(URL url) {
        List<URL> urls = new ArrayList<URL>();
        // 通过消费者url获得订阅的服务的监听器
        Map<String, List<URL>> notifiedUrls = getNotified().get(url);
        // 获得注册的服务url集合
        if (notifiedUrls != null && notifiedUrls.size() > 0) {
            for (List<URL> values : notifiedUrls.values()) {
                urls.addAll(values);
            }
        }
        // 如果为空，则从内存缓存properties获得相关value，并且返回为注册的服务
        if (urls.isEmpty()) {
            List<URL> cacheUrls = getCacheUrls(url);
            if (cacheUrls != null && !cacheUrls.isEmpty()) {
                urls.addAll(cacheUrls);
            }
        }
        // 如果还是为空则从缓存registered中获得已注册 服务URL 集合
        if (urls.isEmpty()) {
            for (URL u : getRegistered()) {
                if (UrlUtils.isMatch(url, u)) {
                    urls.add(u);
                }
            }
        }
        // 如果url携带的配置服务接口为*，也就是所有服务，则从缓存subscribed获得已注册 服务URL 集合
        if (Constants.ANY_VALUE.equals(url.getServiceInterface())) {
            for (URL u : getSubscribed().keySet()) {
                if (UrlUtils.isMatch(url, u)) {
                    urls.add(u);
                }
            }
        }
        return urls;
    }

    public MulticastSocket getMutilcastSocket() {
        return multicastSocket;
    }

    public Map<URL, Set<URL>> getReceived() {
        return received;
    }

}
