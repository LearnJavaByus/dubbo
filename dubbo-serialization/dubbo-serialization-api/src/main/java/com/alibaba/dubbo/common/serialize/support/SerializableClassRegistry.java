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

import com.esotericsoftware.kryo.Serializer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 该类提供一个序列化统一的注册中心，其实就是封装了可序列化类的集合
 */
public abstract class SerializableClassRegistry {
    /**
     * 可序列化类类的集合
     */
    private static final Map<Class, Object> registrations = new LinkedHashMap<Class, Object>();

    /**
     * only supposed to be called at startup time  把可序列化的类加入到集合
     */
    public static void registerClass(Class clazz) {
        registerClass(clazz, null);
    }

    /**
     * only supposed to be called at startup time
     */
    public static void registerClass(Class clazz, Serializer serializer) {
        if (clazz == null) {
            throw new IllegalArgumentException("Class registered to kryo cannot be null!");
        }
        registrations.put(clazz, serializer);
    }
    /**
     * 获得可序列化的类的集合
     * @return
     */
    public static Map<Class, Object> getRegisteredClasses() {
        return registrations;
    }
}
