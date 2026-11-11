package io.yuan.octopus.service;

import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.util.*;
import java.util.regex.*;

/** Configuration structure and loading references only; no connection addresses or secret/default values. */
final class ConfigurationEvidence {
    private ConfigurationEvidence() {}
    private static final Pattern PLACEHOLDER=Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.-]*)(?::[^}]*)?}");
    static Map<String,Object> project(String raw,boolean properties) {
        var entries=new ArrayList<Map<String,Object>>();var warnings=new ArrayList<String>();
        try {
            if(properties){var values=new Properties();values.load(new java.io.StringReader(raw));walk(values,"",0,0,entries);}
            else {var options=new LoaderOptions();options.setCodePointLimit(200000);options.setMaxAliasesForCollections(20);int document=0;
                for(Object value:new Yaml(new SafeConstructor(options)).loadAll(raw))walk(value,"",document++,0,entries);}
        } catch(Exception e){warnings.add("配置未能完整解析；仅已解析的结构可作为证据");}
        if(entries.size()>=500)warnings.add("配置结构达到 500 项限制，未覆盖的内容需要进一步读取");
        return Map.of("entries",entries,"warnings",warnings,"boundary","仅仓库中声明的配置结构及加载引用；未读取配置中心或部署实际值，未提供默认值、连接地址或凭据。");
    }
    private static void walk(Object value,String prefix,int document,int depth,List<Map<String,Object>> out) {
        if(depth>16 || out.size()>=500)return;
        if(value instanceof Map<?,?> map){for(var e:map.entrySet()){
            if(!(e.getKey() instanceof String key) || !key.matches("[A-Za-z_][A-Za-z0-9_.-]{0,150}"))continue;
            walk(e.getValue(),prefix.isEmpty()?key:prefix+"."+key,document,depth+1,out);
        }} else if(value instanceof List<?> list){for(int i=0;i<list.size() && out.size()<500;i++)walk(list.get(i),prefix+"["+i+"]",document,depth+1,out);}
        else {
            var item=new LinkedHashMap<String,Object>();item.put("key",prefix);item.put("document",document);
            item.put("type",value==null?"null":value instanceof Boolean?"boolean":value instanceof Number?"number":"string");
            String text=Objects.toString(value,"");var refs=new LinkedHashSet<String>();var matcher=PLACEHOLDER.matcher(text);while(matcher.find())refs.add(matcher.group(1));
            if(!refs.isEmpty()){item.put("placeholderKeys",refs);item.put("containsDefault",text.matches("(?s).*\\$\\{[^}]*:[^}]*}.*"));}
            String key=prefix.replaceAll("\\[\\d+\\]","");
            // These are loading/profile selectors, not arbitrary application defaults.
            if(key.matches("spring\\.(profiles\\.(active|include|group\\.[A-Za-z0-9_.-]+)|config\\.activate\\.on-profile)") && text.matches("[A-Za-z0-9_., !&|()-]{1,160}"))item.put("selector",text);
            if(key.matches("spring\\.cloud\\.nacos\\.config\\.(.*\\.)?(data-id|group|namespace|file-extension)") && text.matches("[A-Za-z0-9_.-]{1,160}"))item.put("configurationReference",text);
            if(key.matches("spring\\.(config\\.(import|name|location|additional-location)|cloud\\.nacos\\.config\\..*data-id)")) {
                var locations=new ArrayList<Map<String,String>>();
                for(String token:text.split(",")) {
                    String t=token.trim();boolean optional=t.startsWith("optional:");if(optional)t=t.substring(9);
                    String scheme=t.contains(":")?t.substring(0,t.indexOf(':')):"resource";
                    if(!Set.of("nacos","configserver","classpath","classpath*","file","resource").contains(scheme))continue;
                    var location=new LinkedHashMap<String,String>();location.put("kind",scheme);location.put("optional",Boolean.toString(optional));
                    String name=t.contains(":")?t.substring(t.indexOf(':')+1):t;name=name.split("\\?",2)[0];
                    if(name.matches("[A-Za-z0-9_.-]{1,160}"))location.put("name",name);
                    locations.add(location);
                }
                if(!locations.isEmpty())item.put("imports",locations);
            }
            if(key.matches("spring\\.cloud\\.(nacos\\.config|config)\\..*(enabled|refresh-enabled|refresh|fail-fast)") && Set.of("true","false").contains(text))item.put("declaredFlag",text);
            out.add(item);
        }
    }
}
