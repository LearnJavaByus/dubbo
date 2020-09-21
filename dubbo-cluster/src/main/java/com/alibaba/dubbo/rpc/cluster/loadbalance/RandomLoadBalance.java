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
package com.alibaba.dubbo.rpc.cluster.loadbalance;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;

import java.util.List;
import java.util.Random;

/**
 * random load balance.
 *
 * 该类是基于权重随机算法的负载均衡实现类，我们先来讲讲原理，
 * 比如我有有一组服务器 servers = [A, B, C]，他们他们对应的权重为 weights = [6, 3, 1]，权重总和为10，
 * 现在把这些权重值平铺在一维坐标值上，分别出现三个区域，A区域为[0,6)，B区域为[6,9)，C区域为[9,10)，然后产生一个[0, 10)的随机数，
 * 看该数字落在哪个区间内，就用哪台服务器，这样权重越大的，被击中的概率就越大。
 *
 */
public class RandomLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "random";
    /**
     * 随机数产生器
     */
    private final Random random = new Random();

    /**
     * 当然 RandomLoadBalance 也存在一定的缺点，当调用次数比较少时，Random 产生的随机数可能会比较集中，此时多数请求会落到同一台服务器上，不过影响不大。
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // 获得服务长度
        int length = invokers.size(); // Number of invokers
        // 总的权重
        int totalWeight = 0; // The sum of weights
        // 是否有相同的权重
        boolean sameWeight = true; // Every invoker has the same weight?
        // 遍历每个服务，计算相应权重
        for (int i = 0; i < length; i++) {
            int weight = getWeight(invokers.get(i), invocation);
            // 计算总的权重值
            totalWeight += weight; // Sum
            // 如果前一个服务的权重值不等于后一个则sameWeight为false
            if (sameWeight && i > 0
                    && weight != getWeight(invokers.get(i - 1), invocation)) {
                sameWeight = false;
            }
        }
        // 如果每个服务权重都不同，并且总的权重值不为0
        if (totalWeight > 0 && !sameWeight) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.
            int offset = random.nextInt(totalWeight);
            // Return a invoker based on the random value.
            // 循环让 offset 数减去服务提供者权重值，当 offset 小于0时，返回相应的 Invoker。
            // 举例说明一下，我们有 servers = [A, B, C]，weights = [6, 3, 1]，offset = 7。
            // 第一次循环，offset - 6 = 1 > 0，即 offset > 6，
            // 表明其不会落在服务器 A 对应的区间上。
            // 第二次循环，offset - 3 = -2 < 0，即 6 < offset < 9，
            // 表明其会落在服务器 B 对应的区间上
            for (int i = 0; i < length; i++) {
                offset -= getWeight(invokers.get(i), invocation);
                if (offset < 0) {
                    return invokers.get(i);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        // 如果所有服务提供者权重值相同，此时直接随机返回一个即可
        return invokers.get(random.nextInt(length));
    }

}
