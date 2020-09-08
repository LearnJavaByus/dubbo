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
package com.alibaba.dubbo.common.extension;

/**
 * ExtensionFactory
 *
 * 该接口是扩展工厂接口类，它本身也是一个扩展接口，有SPI的注解。该工厂接口提供的就是获取实现类的实例，
 * 它也有两种扩展实现，分别是SpiExtensionFactory和SpringExtensionFactory代表着两种不同方式去获取实例。
 * 而具体选择哪种方式去获取实现类的实例，则在适配器AdaptiveExtensionFactory中制定了规则。
 */
@SPI
public interface ExtensionFactory {

    /**
     * Get extension.
     *
     * @param type object type.
     * @param name object name.
     * @return object instance.
     */
    <T> T getExtension(Class<T> type, String name);

}
