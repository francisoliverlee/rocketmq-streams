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
package org.apache.rocketmq.streams.filter.operator.expression;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.rocketmq.streams.common.cache.compress.impl.IntValueKV;
import org.apache.rocketmq.streams.common.model.NameCreator;
import org.apache.rocketmq.streams.common.utils.MapKeyUtil;
import org.apache.rocketmq.streams.common.utils.StringUtil;
import org.apache.rocketmq.streams.filter.context.RuleContext;
import org.apache.rocketmq.streams.filter.function.expression.RegexFunction;
import org.apache.rocketmq.streams.filter.operator.Rule;

public class GroupExpression extends Expression<List<Expression>> {
    protected Rule rule;
    protected String varName;
    protected static IntValueKV cache = new IntValueKV(3000000);
    protected boolean isOrRelation = true;//是否是or关系
    protected Map<String, Boolean> expressionName2Result = new HashMap<>();//正则类表达式的名字和结果的映射
    protected Set<String> regeExpressionNameSet = new HashSet<>();//正则类表达式的名字

    public GroupExpression(Rule rule, String varName, boolean isOrRelation) {
        this.rule = rule;
        this.varName = varName;
        this.isOrRelation = isOrRelation;
        this.setConfigureName(NameCreator.createNewName("expression.group"));
        value = new ArrayList<>();
        this.setNameSpace(rule.getNameSpace());
    }

    @Override
    public Boolean doAction(RuleContext context, Rule rule) {
        String varValue = context.getMessage().getMessageBody().getString(varName);
        String key = MapKeyUtil.createKey(getNameSpace(), rule.getConfigureName(), getConfigureName(), varValue);
        Integer value = cache.get(key);
        if (value == null) {
            Boolean isMatch = executeMatch(context, rule);
            if (isMatch) {
                value = 1;
            } else {
                value = 0;
            }
            cache.put(key, value);
        }
        return value > 0;
    }

    @Override
    public String toString() {
        return "";
    }

    @Override
    public Set<String> getDependentFields(Map<String, Expression> expressionMap) {
        Set<String> set = new HashSet<>();
        set.add(varName);

        return set;
    }

    private Boolean executeMatch(RuleContext context, Rule rule) {
        for (Expression expression : getValue()) {
            Boolean result = expressionName2Result.get(expression.getConfigureName());
            boolean isMatch = false;
            if (result != null) {
                isMatch = result;
            } else {
                isMatch = expression.doAction(context, rule);
            }
            if (isOrRelation) {
                if (isMatch) {
                    return true;
                }
            } else {
                if (!isMatch) {
                    return false;
                }
            }
        }
        if (isOrRelation) {
            return false;
        } else {
            return true;
        }
    }

    public void addExpressionName(Expression expression) {
        if (RegexFunction.isRegex(expression.getFunctionName())) {
            regeExpressionNameSet.add(expression.getConfigureName());
        }
        getValue().add(expression);
    }

    public void setRegexResult(Set<String> allRegexResult) {
        Iterator<String> it = regeExpressionNameSet.iterator();
        while (it.hasNext()) {
            String name = it.next();
            Boolean value = allRegexResult.contains(name);
            if (value == null) {
                value = false;
            }
            expressionName2Result.put(name, value);

        }
    }

    public int size() {
        return getValue().size();
    }

    public Collection<? extends String> getAllExpressionNames() {
        Set<String> names = new HashSet<>();
        for (Expression expression : getValue()) {
            names.add(expression.getConfigureName());
        }
        return names;
    }

    public static void main(String[] args) {
        String content = "abdfdfd";
        String regex = "ab.*fd";
        System.out.println(StringUtil.matchRegex(content, regex));
    }
}
