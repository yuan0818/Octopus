package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

import com.google.gson.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Service
public class BusinessTestImpactBridge {
    private final BusinessKnowledgeStore store;
    private final String queryUrl;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public BusinessTestImpactBridge(
            BusinessKnowledgeStore store,
            @Value("${scenario.testing.query-url:http://localhost:8080}") String queryUrl) {
        this.store = store;
        this.queryUrl = queryUrl;
    }

    public record AffectedPoint(
            String flowId,
            int flowRevision,
            String testPointId,
            List<String> affectedNodes,
            List<String> prerequisiteNodes,
            List<String> evidenceRefs,
            String reason) {}

    public record Impact(
            Map<String, Object> codeImpact,
            List<AffectedPoint> testPoints,
            List<String> unmappedEndpoints) {}

    public Impact analyze(Map<String, Object> request) {
        Map<String, Object> code = post("/api/query/business-test-impact", request);
        JsonObject json = GSON.toJsonTree(code).getAsJsonObject();
        List<Operation> operations = store.operations();
        Map<String, Set<String>> evidenceByOperation = new HashMap<>();
        List<String> unmapped = new ArrayList<>();
        for (var evidence : json.getAsJsonArray("evidence")) {
            JsonObject e = evidence.getAsJsonObject(), endpoint = e.getAsJsonObject("endpoint");
            boolean matched = false;
            for (Operation operation : operations) {
                boolean match =
                        operation.implementations().stream()
                                .anyMatch(
                                        i ->
                                                i.projectId()
                                                                == endpoint.get("projectId")
                                                                        .getAsLong()
                                                        && i.branch()
                                                                .equals(
                                                                        endpoint.get("branch")
                                                                                .getAsString())
                                                        && i.commitId()
                                                                .equals(
                                                                        endpoint.get("commitId")
                                                                                .getAsString())
                                                        && canonicalSignature(i.signature())
                                                                .equals(
                                                                        canonicalSignature(
                                                                                endpoint.get(
                                                                                                "signature")
                                                                                        .getAsString())));
                if (match
                        && ("*".equals(endpoint.get("httpMethod").getAsString())
                                || operation
                                        .method()
                                        .equals(endpoint.get("httpMethod").getAsString()))
                        && operation.path().equals(endpoint.get("path").getAsString())) {
                    evidenceByOperation
                            .computeIfAbsent(operation.operationId(), k -> new TreeSet<>())
                            .add(e.get("id").getAsString());
                    matched = true;
                }
            }
            if (!matched)
                unmapped.add(
                        endpoint.get("httpMethod").getAsString()
                                + " "
                                + endpoint.get("path").getAsString());
        }
        List<AffectedPoint> points = new ArrayList<>();
        for (var revision : store.flows())
            if ("CONFIRMED".equals(revision.status())) {
                Flow flow = revision.definition();
                if(flow.steps()!=null) { points.addAll(orderedImpact(flow,evidenceByOperation));continue; }
                Map<String, Set<String>> reasons = new TreeMap<>();
                Map<String, String> producers = new HashMap<>();
                flow.nodes().forEach(n -> n.outputs().forEach(o -> producers.put(o.key(), n.id())));
                Map<String, Set<String>> dependencies = new TreeMap<>();
                flow.nodes().forEach(n -> dependencies.put(n.id(), new TreeSet<>()));
                for (var node : flow.nodes()) {
                    String operationId =
                            node.operationRef().substring(0, node.operationRef().lastIndexOf('@'));
                    if (evidenceByOperation.containsKey(operationId))
                        reasons.put(node.id(), new TreeSet<>(evidenceByOperation.get(operationId)));
                    node.inputs().values().stream()
                            .filter(b -> "context".equals(b.source()))
                            .forEach(
                                    b -> {
                                        String p = producers.get(String.valueOf(b.value()));
                                        if (p != null) dependencies.get(node.id()).add(p);
                                    });
                    node.states()
                            .forEach(
                                    s -> {
                                        for (String key :
                                                List.of(
                                                        s.entityContext(),
                                                        s.observedContext(),
                                                        s.observedEntityContext())) {
                                            String p = producers.get(key);
                                            if (p != null) dependencies.get(node.id()).add(p);
                                        }
                                    });
                }
                flow.dependencies().forEach(d -> dependencies.get(d.to()).add(d.from()));
                boolean changed;
                do {
                    changed = false;
                    for (var entry : dependencies.entrySet())
                        for (String dependency : entry.getValue())
                            if (reasons.containsKey(dependency))
                                changed |=
                                        reasons.computeIfAbsent(
                                                        entry.getKey(), k -> new TreeSet<>())
                                                .addAll(reasons.get(dependency));
                } while (changed);
                for (var point : flow.testPoints()) {
                    Set<String> evidence = new TreeSet<>();
                    point.covers().forEach(n -> evidence.addAll(reasons.getOrDefault(n, Set.of())));
                    if (evidence.isEmpty()) continue;
                    // v0.1 executes the complete confirmed flow, preserving all prerequisites and
                    // independent checks.
                    points.add(
                            new AffectedPoint(
                                    flow.flowId(),
                                    flow.revision(),
                                    point.id(),
                                    new ArrayList<>(reasons.keySet()),
                                    flow.nodes().stream()
                                            .map(FlowNode::id)
                                            .filter(n -> !reasons.containsKey(n))
                                            .sorted()
                                            .toList(),
                                    new ArrayList<>(evidence),
                                    "Implementation path reaches a referenced operation;"
                                        + " context/state/business dependencies propagate to this"
                                        + " test point. The complete flow is executed."));
                }
            }
        var report = new Impact(code, points, unmapped.stream().distinct().sorted().toList());
        String digest = store.saveImpact(report);
        return new Impact(
                code,
                points.stream()
                        .map(
                                p ->
                                        new AffectedPoint(
                                                p.flowId(),
                                                p.flowRevision(),
                                                p.testPointId(),
                                                p.affectedNodes(),
                                                p.prerequisiteNodes(),
                                                p.evidenceRefs().stream()
                                                        .map(id -> digest + ":" + id)
                                                        .toList(),
                                                p.reason()))
                        .toList(),
                report.unmappedEndpoints());
    }

    static List<AffectedPoint> orderedImpact(Flow flow,Map<String,Set<String>> evidenceByOperation) {
        List<AffectedPoint> result=new ArrayList<>();
        for(var point:flow.testPoints()) {
            var business=OrderedFlows.prefix(flow,point).stream().flatMap(s->s.calls().stream()).toList();
            var executed=new ArrayList<>(business);
            Set<String> known=new HashSet<>();
            business.stream().filter(c->point.suppressedOutputCalls()==null || !point.suppressedOutputCalls().contains(c.id()))
                    .forEach(c->c.outputs().forEach(o->known.add(o.key())));
            if(flow.cleanup()!=null) flow.cleanup().stream().filter(c->known.contains(c.resourceKey())).forEach(c->executed.add(c.call()));
            List<String> affected=new ArrayList<>();Set<String> evidence=new TreeSet<>();
            for(var call:executed) {
                String operationId=call.operationRef().substring(0,call.operationRef().lastIndexOf('@'));
                if(evidenceByOperation.containsKey(operationId)) {affected.add(call.id());evidence.addAll(evidenceByOperation.get(operationId));}
            }
            if(!evidence.isEmpty()) result.add(new AffectedPoint(flow.flowId(),flow.revision(),point.id(),affected,
                    business.stream().map(FlowNode::id).filter(id->!affected.contains(id)).toList(),List.copyOf(evidence),
                    "A changed API occurs in the fixed case scope or its cleanup. Execute the complete declared step prefix without path pruning."));
        }
        return result;
    }

    public List<Map<String, Object>> endpoints(long projectId, String branch, String commit) {
        try {
            String url =
                    queryUrl
                            + "/api/query/endpoints?projectId="
                            + projectId
                            + "&branch="
                            + java.net.URLEncoder.encode(
                                    branch, java.nio.charset.StandardCharsets.UTF_8)
                            + "&commitId="
                            + java.net.URLEncoder.encode(
                                    commit, java.nio.charset.StandardCharsets.UTF_8);
            var response =
                    http.send(
                            HttpRequest.newBuilder(URI.create(url))
                                    .timeout(Duration.ofSeconds(30))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new IllegalStateException("Endpoint query failed: " + response.statusCode());
            var root = JsonParser.parseString(response.body()).getAsJsonObject();
            return GSON.fromJson(
                    root.get("data"),
                    new com.google.gson.reflect.TypeToken<
                            List<Map<String, Object>>>() {}.getType());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Code intelligence unavailable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Query interrupted", e);
        }
    }

    private Map<String, Object> post(String path, Object body) {
        try {
            var response =
                    http.send(
                            HttpRequest.newBuilder(URI.create(queryUrl + path))
                                    .timeout(Duration.ofSeconds(120))
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(canonical(body)))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new IllegalStateException("Code impact failed: " + response.statusCode());
            var root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("data") || !root.get("data").isJsonObject())
                throw new IllegalStateException("Invalid impact response");
            return GSON.fromJson(
                    root.get("data"),
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Code intelligence unavailable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Impact query interrupted", e);
        }
    }

    private static String canonicalSignature(String signature) {
        return signature.replaceAll("\\s+", "");
    }
}
