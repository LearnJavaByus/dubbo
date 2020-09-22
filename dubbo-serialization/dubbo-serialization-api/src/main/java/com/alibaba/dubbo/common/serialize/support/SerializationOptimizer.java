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
package com.alibaba.dubbo.common.serialize.support;

import java.util.Collection;

/**
 * This class can be replaced with the contents in config file, but for now I think the class is easier to write
 *
 * 该接口序列化优化器接口，在 Kryo 、FST 中，支持配置需要优化的类。业务系统中，
 * 可以实现自定义的 SerializationOptimizer，进行配置。或者使用文件来配置也是一个选择。
 */
public interface SerializationOptimizer {
    /**
     * 需要序列化的类的集合
     * @return
     */
    Collection<Class> getSerializableClasses();
}
