package io.yuan.octopus.scenario.testing;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.lang.reflect.*;
import java.util.*;

/** Safe structural diagnostics: schema paths and types only, never rejected model text or field names. */
final class RepositoryModelJson {
    private RepositoryModelJson() {}
    static FlowFailure invalid(String reason) { return new FlowFailure(FlowFailure.Code.MODEL_RESPONSE_INVALID,null,reason); }
    static <T> T parse(FlowModelClient.Response response,Class<T> type) {
        if(response==null || response.content()==null || response.content().isBlank())throw invalid("EMPTY_RESPONSE：模型返回为空");
        if(response.content().length()>100000)throw invalid("OUTPUT_TOO_LARGE：返回超过十万字符限制");
        String text=response.content().strip().replaceFirst("(?i)^```json\\s*","").replaceFirst("^```\\s*","").replaceFirst("\\s*```$","");
        JsonElement json;
        try {
            var reader=new JsonReader(new StringReader(text));reader.setStrictness(Strictness.STRICT);
            json=JsonParser.parseReader(reader);
            if(reader.peek()!=JsonToken.END_DOCUMENT)throw invalid("JSON_SYNTAX：只能返回一个完整 JSON 对象");
        } catch(FlowFailure error) {throw error;}
        catch(Exception error) {throw invalid("JSON_SYNTAX：JSON 语法无效"+(!text.endsWith("}")?"，返回可能未完整输出":"")+"；请重新生成完整对象，不要拼接说明或多个对象");}
        if(!json.isJsonObject())throw invalid("SCHEMA_TYPE：根节点应为对象，实际为"+kind(json));
        shape(json,type,type.getSimpleName());
        try { return TestingJson.read(json,type); }
        catch(RuntimeException error) {throw invalid("SCHEMA_TYPE：字段类型或数值范围不符合约定");}
    }
    private static String kind(JsonElement value) {
        if(value.isJsonNull())return "null";if(value.isJsonArray())return "数组";if(value.isJsonObject())return "对象";
        if(value.getAsJsonPrimitive().isBoolean())return "布尔值";
        return value.getAsJsonPrimitive().isNumber()?"数字":"字符串";
    }
    private static void expected(boolean match,String path,String expected,JsonElement value) {
        if(!match)throw invalid("SCHEMA_TYPE："+path+" 应为"+expected+"，实际为"+kind(value));
    }
    private static void shape(JsonElement value,Type type,String path) {
        if(value.isJsonNull())return; // Required/non-null constraints are enforced by stage validators.
        if(type instanceof ParameterizedType p && p.getRawType()==List.class) {
            expected(value.isJsonArray(),path,"数组",value);
            int i=0;for(var item:value.getAsJsonArray())shape(item,p.getActualTypeArguments()[0],path+"["+(i++)+"]");
        } else if(type instanceof ParameterizedType p && p.getRawType()==Map.class) {
            expected(value.isJsonObject(),path,"对象",value);
            // Map keys may contain model-generated data. Only expose the schema location.
            for(var entry:value.getAsJsonObject().entrySet())shape(entry.getValue(),p.getActualTypeArguments()[1],path+"[*]");
        } else if(type instanceof Class<?> c && c.isRecord()) {
            expected(value.isJsonObject(),path,"对象",value);
            var fields=new HashMap<String,Type>();for(var f:c.getRecordComponents())fields.put(f.getName(),f.getGenericType());
            for(var entry:value.getAsJsonObject().entrySet()) {
                if(!fields.containsKey(entry.getKey()))throw invalid("SCHEMA_FIELD："+path+" 含未约定字段，请只使用当前阶段的字段结构");
                shape(entry.getValue(),fields.get(entry.getKey()),path+"."+entry.getKey());
            }
        } else if(type==String.class)expected(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(),path,"字符串",value);
        else if(type==Boolean.class || type==boolean.class)expected(value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean(),path,"布尔值",value);
        else if(type==Integer.class || type==int.class) {
            expected(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(),path,"整数",value);
            try {value.getAsBigDecimal().intValueExact();}catch(ArithmeticException error){throw invalid("SCHEMA_TYPE："+path+" 应为有效范围内的整数");}
        }
    }
}
