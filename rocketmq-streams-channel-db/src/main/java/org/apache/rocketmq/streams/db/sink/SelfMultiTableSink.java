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
package org.apache.rocketmq.streams.db.sink;

import org.apache.rocketmq.streams.common.channel.split.ISplit;
import org.apache.rocketmq.streams.common.configurable.IAfterConfiguableRefreshListerner;
import org.apache.rocketmq.streams.common.configurable.IConfigurableService;
import org.apache.rocketmq.streams.common.context.IMessage;
import org.apache.rocketmq.streams.common.functions.MultiTableSplitFunction;
import org.apache.rocketmq.streams.common.utils.Base64Utils;
import org.apache.rocketmq.streams.common.utils.InstantiationUtil;

public class SelfMultiTableSink extends AbstractMultiTableSink implements IAfterConfiguableRefreshListerner {
    protected String multiTableSplitFunctionSerializeValue;//用户自定义的operator的序列化字节数组，做了base64解码
    protected transient MultiTableSplitFunction<IMessage> multiTableSplitFunction;

    public SelfMultiTableSink(String url, String userName, String password, MultiTableSplitFunction<IMessage> multiTableSplitFunction) {
        super(url, userName, password);
        this.multiTableSplitFunction = multiTableSplitFunction;
        byte[] bytes = InstantiationUtil.serializeObject(multiTableSplitFunction);
        multiTableSplitFunctionSerializeValue = Base64Utils.encode(bytes);
    }

    @Override
    protected String createTableName(String splitId) {
        return multiTableSplitFunction.createTableFromSplitId(splitId);
    }

    @Override
    protected ISplit getSplitFromMessage(IMessage message) {
        return multiTableSplitFunction.createSplit(message);
    }

    @Override
    public void doProcessAfterRefreshConfigurable(IConfigurableService configurableService) {
        byte[] bytes = Base64Utils.decode(multiTableSplitFunctionSerializeValue);
        this.multiTableSplitFunction = InstantiationUtil.deserializeObject(bytes);
    }
}
