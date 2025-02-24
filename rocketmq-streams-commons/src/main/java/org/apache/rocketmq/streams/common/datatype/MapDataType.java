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
package org.apache.rocketmq.streams.common.datatype;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.rocketmq.streams.common.utils.ContantsUtil;
import org.apache.rocketmq.streams.common.utils.DataTypeUtil;
import org.apache.rocketmq.streams.common.utils.PrintUtil;
import org.apache.rocketmq.streams.common.utils.StringUtil;

public class MapDataType extends GenericParameterDataType<Map> {

    private static final long serialVersionUID = 165975791986907630L;

    private final static String MAP_KEY = "key";

    private final static String MAP_VALUE = "value";

    private DataType keyParadigmType;

    private DataType valueParadigmType;

    public MapDataType(Class clazz, DataType keyParadigmType, DataType valueParadigmType) {
        setDataClazz(clazz);
        this.keyParadigmType = keyParadigmType;
        this.valueParadigmType = valueParadigmType;
        this.setGenericParameterStr(createGenericParameterStr());
    }

    public MapDataType(DataType keyParadigmType, DataType valueParadigmType) {
        this(Map.class, keyParadigmType, valueParadigmType);
        this.setGenericParameterStr(createGenericParameterStr());
    }

    @Override
    public void setDataClazz(Class dataClazz) {
        this.dataClazz = Map.class;
    }

    public MapDataType() {
    }

    @Override
    public String toDataJson(Map value) {
        JSONArray mapJson = new JSONArray();
        for (Map.Entry entry : (Iterable<Map.Entry>)value.entrySet()) {
            Object keyObject = entry.getKey();
            Object valueObject = entry.getValue();
            JSONObject itemJsonObject = new JSONObject();
            itemJsonObject.put(MAP_KEY, keyParadigmType.toDataJson(keyObject));
            itemJsonObject.put(MAP_VALUE, valueParadigmType.toDataJson(valueObject));
            mapJson.add(itemJsonObject);
        }
        return mapJson.toJSONString();
    }

    @Override
    public Map getData(String jsonValue) {
        if (StringUtil.isEmpty(jsonValue)) {
            return null;
        }
        if (isQuickModel(jsonValue)) {
            jsonValue = createJsonValue(jsonValue);
        }
        Map map = new HashMap();
        JSONArray mapJson = JSON.parseArray(jsonValue);
        for (int i = 0; i < mapJson.size(); i++) {
            JSONObject itemJson = mapJson.getJSONObject(i);
            String keyJson = itemJson.getString(MAP_KEY);
            String valueJson = itemJson.getString(MAP_VALUE);
            Object key = keyJson;
            Object value = valueJson;
            if (keyParadigmType != null) {
                key = keyParadigmType.getData(keyJson);
            }
            if (valueParadigmType != null) {
                value = valueParadigmType.getData(valueJson);
            }
            map.put(key, value);
        }
        return map;
    }

    private String createJsonValue(String jsonValue) {
        String value = jsonValue;
        Map<String, String> flag2ExpressionStr = new HashMap<>();
        boolean containsContant = ContantsUtil.containContant(jsonValue);
        if (containsContant) {
            List<String> startFlags = new ArrayList<>();

            startFlags.add(",'");
            startFlags.add(":'");
            List<String> endFlags = new ArrayList<>();
            endFlags.add("',");

            endFlags.add("':");
            value = ContantsUtil.doConstantReplace(jsonValue, flag2ExpressionStr, 1, startFlags, endFlags);
        }
        JSONArray jsonArray = new JSONArray();
        String[] values = value.split(",");
        for (int i = 0; i < values.length; i++) {
            String[] kv = values[i].split(":");
            JSONObject jsonObject = new JSONObject();
            String key = kv[0];
            String tmpValue = kv[1];
            if (containsContant) {
                key = ContantsUtil.restore(key, flag2ExpressionStr);
                tmpValue = ContantsUtil.restore(tmpValue, flag2ExpressionStr);
                if (ContantsUtil.isContant(tmpValue)) {
                    tmpValue = tmpValue.substring(1, tmpValue.length() - 1);
                }
                if (ContantsUtil.isContant(key)) {
                    key = key.substring(1, key.length() - 1);
                }
            }
            jsonObject.put(MAP_KEY, key);
            jsonObject.put(MAP_VALUE, tmpValue);
            jsonArray.add(jsonObject);
        }
        return jsonArray.toJSONString();
    }

    protected boolean isQuickModel(String jsonValue) {
        if (StringUtil.isEmpty(jsonValue)) {
            return false;
        }
        if (!jsonValue.trim().startsWith("{") && !jsonValue.trim().startsWith("[") && keyParadigmType
            .matchClass(String.class) && valueParadigmType.matchClass(String.class)) {
            return true;
        }
        return false;
    }

    @Override
    public String getName() {
        return Map.class.getSimpleName();
    }

    public static String getTypeName() {
        return "kv";
    }

    @Override
    public void parseGenericParameter(String genericParameterString) {
        if (StringUtil.isEmpty(genericParameterString)) {
            return;
        }
        genericParameterString = genericParameterString.trim();
        int index = Map.class.getName().length() + 1;
        String subClassString = genericParameterString.substring(index, genericParameterString.length() - 1);
        String[] kv = new String[2];
        if (subClassString.contains("<")) {
            kv = splitStr(subClassString);
        } else {
            kv = subClassString.split(",");
        }
        this.keyParadigmType = createDataType(kv[0]);
        this.valueParadigmType = createDataType(kv[1]);
    }

    @Override
    public String toDataStr(Map map) {
        StringBuilder stringBuilder = new StringBuilder();
        if (map == null) {
            return stringBuilder.toString();
        }
        Iterator<Map.Entry<Object, Object>> it = map.entrySet().iterator();
        boolean isFirst = true;
        while (it.hasNext()) {
            Map.Entry<Object, Object> entry = it.next();
            Object key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || value == null) {
                continue;
            }
            String keyJson = keyParadigmType.toDataJson(key);
            if (keyJson.contains(",") || keyJson.indexOf(":") != 1) {
                keyJson = "'" + keyJson + "'";
            }
            String valuejson = valueParadigmType.toDataJson(value);
            if (valuejson.contains(",") || valuejson.indexOf(":") != 1) {
                valuejson = "'" + valuejson + "'";
            }
            if (isFirst) {
                isFirst = false;
            } else {
                stringBuilder.append(",");
            }
            stringBuilder.append(keyJson + ":" + valuejson);
        }
        return stringBuilder.toString();
    }

    private String[] splitStr(String subClassString) {
        int sign = 0;
        StringBuilder key = new StringBuilder();
        int index = -1;
        for (int i = 0; i < subClassString.length(); i++) {
            String word = subClassString.substring(i, i + 1);
            if (word.equals("<")) {
                sign++;
            }
            if (word.equals(">")) {
                sign--;
            }
            if (word.equals(",") && sign == 0) {
                index = i;
                break;
            }
            key.append(word);
        }
        String[] kv = new String[2];
        kv[0] = key.toString();
        kv[1] = subClassString.substring(index + 1);
        return kv;
    }

    private DataType createDataType(String genericParameterString) {
        int index = genericParameterString.indexOf("<");
        if (index == -1) {
            Class clazz = createClass(genericParameterString);
            return DataTypeUtil.getDataTypeFromClass(clazz);
        }
        String className = genericParameterString.substring(0, index);
        Class clazz = createClass(className);
        GenericParameterDataType genericParamterDataType =
            (GenericParameterDataType)DataTypeUtil.getDataTypeFromClass(clazz);
        genericParamterDataType.parseGenericParameter(genericParameterString);
        return genericParamterDataType;
    }

    @Override
    protected String createGenericParameterStr() {
        String keyStr = createGenericParameterStr(keyParadigmType);
        String valueStr = createGenericParameterStr(valueParadigmType);
        return Map.class.getName() + "<" + keyStr + "," + valueStr + ">";
    }

    @Override
    public boolean matchClass(Class clazz) {
        return Map.class.isAssignableFrom(clazz);
    }

    @Override
    public DataType create() {
        return new MapDataType();
    }

    @Override
    public String getDataTypeName() {
        return getTypeName();
    }

    public static void main(String[] args) {
        MapDataType mapDataType = new MapDataType(new StringDataType(), new StringDataType());
        Map<String, Object> values = mapDataType.getData("'d,f':d,'fd:f':'d',e:f");
        PrintUtil.print(values);

    }

    public void setKeyParadigmType(DataType keyParadigmType) {
        this.keyParadigmType = keyParadigmType;
    }

    public void setValueParadigmType(DataType valueParadigmType) {
        this.valueParadigmType = valueParadigmType;
    }
}
