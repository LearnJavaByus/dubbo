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
package com.alibaba.dubbo.remoting.transport.mina;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.transport.AbstractClient;

import org.apache.mina.common.ConnectFuture;
import org.apache.mina.common.IoFuture;
import org.apache.mina.common.IoFutureListener;
import org.apache.mina.common.IoSession;
import org.apache.mina.common.ThreadModel;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.transport.socket.nio.SocketConnector;
import org.apache.mina.transport.socket.nio.SocketConnectorConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mina client.  是基于mina实现的客户端类。
 */
public class MinaClient extends AbstractClient {

    private static final Logger logger = LoggerFactory.getLogger(MinaClient.class);
    /**
     * 套接字连接集合
     */
    private static final Map<String, SocketConnector> connectors = new ConcurrentHashMap<String, SocketConnector>();
    /**
     * 连接的key
     */
    private String connectorKey;
    /**
     * 套接字连接者
     */
    private SocketConnector connector;
    /**
     * 套接字连接者
     */
    private volatile IoSession session; // volatile, please copy reference to use

    public MinaClient(final URL url, final ChannelHandler handler) throws RemotingException {
        super(url, wrapChannelHandler(url, handler));
    }

    @Override
    protected void doOpen() throws Throwable {
        // 用url来作为key
        connectorKey = getUrl().toFullString();
        // 先从集合中取套接字连接
        SocketConnector c = connectors.get(connectorKey);
        if (c != null) {
            connector = c;
        } else { // 如果为空
            // set thread pool. // 如果为空
            connector = new SocketConnector(Constants.DEFAULT_IO_THREADS,
                    Executors.newCachedThreadPool(new NamedThreadFactory("MinaClientWorker", true)));
            // config  // config 获得套接字连接配置
            SocketConnectorConfig cfg = (SocketConnectorConfig) connector.getDefaultConfig();
            cfg.setThreadModel(ThreadModel.MANUAL);
            cfg.getSessionConfig().setTcpNoDelay(true);// 启用TCP_NODELAY
            cfg.getSessionConfig().setKeepAlive(true);// 启用SO_KEEPALIVE
            int timeout = getConnectTimeout();
            // 设置连接超时时间
            cfg.setConnectTimeout(timeout < 1000 ? 1 : timeout / 1000);
            // set codec.  // 设置编解码器
            connector.getFilterChain().addLast("codec", new ProtocolCodecFilter(new MinaCodecAdapter(getCodec(), getUrl(), this)));
            // 加入集合
            connectors.put(connectorKey, connector);
        }
    }

    /**
     * 该方法是客户端连接服务器的实现方法。其中用到了CountDownLatch来代表完成完成事件，它来做一个线程等待，
     * 直到1个线程完成上述的动作，也就是连接完成结束，才释放等待的线程。保证每次只有一条线程去连接，
     * 解决future.awaitUninterruptibly（）死锁问题。
     * @throws Throwable
     */
    @Override
    protected void doConnect() throws Throwable {
        // 连接服务器
        ConnectFuture future = connector.connect(getConnectAddress(), new MinaHandler(getUrl(), this));
        long start = System.currentTimeMillis();
        final AtomicReference<Throwable> exception = new AtomicReference<Throwable>();
        // 用于对线程的阻塞和唤醒
        final CountDownLatch finish = new CountDownLatch(1); // resolve future.awaitUninterruptibly() dead lock
        // 加入监听器
        future.addListener(new IoFutureListener() {
            @Override
            public void operationComplete(IoFuture future) {
                try {
                    if (future.isReady()) {// 如果已经读完了
                        // 创建获得该连接的IoSession实例
                        IoSession newSession = future.getSession();
                        try {
                            // Close old channel 关闭旧的session
                            IoSession oldSession = MinaClient.this.session; // copy reference
                            if (oldSession != null) {
                                try {
                                    if (logger.isInfoEnabled()) {
                                        logger.info("Close old mina channel " + oldSession + " on create new mina channel " + newSession);
                                    }
                                    oldSession.close();  // 关闭连接
                                } finally {
                                    // 移除通道
                                    MinaChannel.removeChannelIfDisconnected(oldSession);
                                }
                            }
                        } finally {
                            if (MinaClient.this.isClosed()) {// 如果MinaClient关闭了
                                try {
                                    if (logger.isInfoEnabled()) {
                                        logger.info("Close new mina channel " + newSession + ", because the client closed.");
                                    }
                                    newSession.close();  // 关闭session
                                } finally {
                                    MinaClient.this.session = null;
                                    MinaChannel.removeChannelIfDisconnected(newSession);
                                }
                            } else {
                                // 设置新的session
                                MinaClient.this.session = newSession;
                            }
                        }
                    }
                } catch (Exception e) {
                    exception.set(e);
                } finally {
                    finish.countDown();// 减少数量，释放所有等待的线程
                }
            }
        });
        try {
            // 当前线程等待，直到锁存器倒计数到零，除非线程被中断，或者指定的等待时间过去
            finish.await(getConnectTimeout(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new RemotingException(this, "client(url: " + getUrl() + ") failed to connect to server " + getRemoteAddress() + " client-side timeout "
                    + getConnectTimeout() + "ms (elapsed: " + (System.currentTimeMillis() - start)
                    + "ms) from netty client " + NetUtils.getLocalHost() + " using dubbo version "
                    + Version.getVersion() + ", cause: " + e.getMessage(), e);
        }
        Throwable e = exception.get();
        if (e != null) {
            throw e;
        }
    }

    @Override
    protected void doDisConnect() throws Throwable {
        try {
            MinaChannel.removeChannelIfDisconnected(session);
        } catch (Throwable t) {
            logger.warn(t.getMessage());
        }
    }

    @Override
    protected void doClose() throws Throwable {
        //release mina resouces.
    }

    @Override
    protected Channel getChannel() {
        IoSession s = session;
        if (s == null || !s.isConnected())
            return null;
        return MinaChannel.getOrAddChannel(s, getUrl(), this);
    }

}
