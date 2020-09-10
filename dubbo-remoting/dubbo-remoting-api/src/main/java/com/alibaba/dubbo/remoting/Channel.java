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
package com.alibaba.dubbo.remoting;

import java.net.InetSocketAddress;

/**
 * Channel. (API/SPI, Prototype, ThreadSafe)
 *
 *通道接口，通道是通讯的载体。
 *
 * @see com.alibaba.dubbo.remoting.Client
 * @see com.alibaba.dubbo.remoting.Server#getChannels()
 * @see com.alibaba.dubbo.remoting.Server#getChannel(InetSocketAddress)
 */
public interface Channel extends Endpoint {

    /**
     * get remote address. / 获得远程地址
     *
     * @return remote address.
     */
    InetSocketAddress getRemoteAddress();

    /**
     * is connected.  // 判断通道是否连接
     *
     * @return connected
     */
    boolean isConnected();

    /**
     * has attribute. // 判断是否有该key的值
     *
     * @param key key.
     * @return has or has not.
     */
    boolean hasAttribute(String key);

    /**
     * get attribute. // 获得该key对应的值
     *
     * @param key key.
     * @return value.
     */
    Object getAttribute(String key);

    /**
     * set attribute. // 添加属性
     *
     * @param key   key.
     * @param value value.
     */
    void setAttribute(String key, Object value);

    /**
     * remove attribute.   // 移除属性
     *
     * @param key key.
     */
    void removeAttribute(String key);

}