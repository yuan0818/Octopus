package io.yuan.octopus.scenario.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Component;

import java.util.*;

/** DAG 保存时校验：结构合法 / 无环 / 无孤儿 / calls 非空。 */
@Component
public class DagValidator {

    private final Gson gson = new Gson();

    public List<String> validate(String dagJson) {
        List<String> errors = new ArrayList<>();
        if (dagJson == null || dagJson.isBlank()) { errors.add("DAG 为空"); return errors; }
        JsonObject dag;
        try { dag = gson.fromJson(dagJson, JsonObject.class); }
        catch (Exception e) { errors.add("DAG 不是合法 JSON: " + e.getMessage()); return errors; }

        JsonArray steps = dag.getAsJsonArray("steps");
        if (steps == null || steps.size() == 0) { errors.add("DAG 无步骤"); return errors; }

        Set<String> ids = new HashSet<>();
        Map<String, List<String>> deps = new LinkedHashMap<>();
        for (JsonElement el : steps) {
            JsonObject step = el.getAsJsonObject();
            String id = str(step, "id");
            if (id == null || id.isBlank()) { errors.add("步骤缺少 id"); continue; }
            if (!ids.add(id)) errors.add("步骤 id 重复: " + id);
            List<String> d = new ArrayList<>();
            JsonArray depArr = step.getAsJsonArray("dependsOn");
            if (depArr != null) for (JsonElement dEl : depArr) d.add(dEl.getAsString());
            deps.put(id, d);

            JsonArray calls = step.getAsJsonArray("calls");
            if ((calls == null || calls.size() == 0) && str(step, "api") == null) {
                errors.add("步骤 " + id + " 既无 calls[] 也无 api");
            }
        }
        for (Map.Entry<String, List<String>> e : deps.entrySet()) {
            for (String d : e.getValue()) {
                if (!ids.contains(d)) errors.add("步骤 " + e.getKey() + " 依赖不存在的步骤: " + d);
            }
        }
        Map<String, Integer> color = new HashMap<>();
        ids.forEach(i -> color.put(i, 0));
        for (String id : ids) {
            if (color.get(id) == 0 && hasCycle(id, deps, color)) errors.add("依赖存在环（涉及 " + id + "）");
        }
        return errors;
    }

    private boolean hasCycle(String node, Map<String, List<String>> deps, Map<String, Integer> color) {
        color.put(node, 1);
        for (String next : deps.getOrDefault(node, List.of())) {
            if (!color.containsKey(next)) continue;
            if (color.get(next) == 1) return true;
            if (color.get(next) == 0 && hasCycle(next, deps, color)) return true;
        }
        color.put(node, 2);
        return false;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && !e.isJsonNull() ? e.getAsString() : null;
    }
}
