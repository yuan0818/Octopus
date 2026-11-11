package io.yuan.octopus.scenario.testing;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.Test;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

class BusinessTestingHttpIntegrationTest {
    final HttpClient http = HttpClient.newHttpClient();

    JsonElement post(String url, Object body, int expected) throws Exception {
        return post(url,body,expected,null);
    }
    JsonElement post(String url, Object body, int expected,String key) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(url));
        if(key!=null) builder.header("Idempotency-Key",key);
        var result =
                http.send(
                        builder
                                .timeout(Duration.ofSeconds(30))
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                TestingJson.canonical(body)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, result.statusCode(), result.body());
        return JsonParser.parseString(result.body());
    }

    JsonElement get(String url) throws Exception {
        var result =
                http.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(30))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode(), result.body());
        return JsonParser.parseString(result.body());
    }

    @Test
    void confirmsPersistsCompilesAndRunsThroughTheRealHttpAndJpaBoundary() throws Exception {
        try (var server = new IsolatedBusinessTestingServer(0)) {
            String url = server.baseUrl;
            post(url + "/operations", TestingFixtures.login(), 200);
            post(url + "/operations", TestingFixtures.verify(), 200);
            var draft = post(url + "/flows", TestingFixtures.flow(), 200).getAsJsonObject();
            var request =
                    Map.of(
                            "flowId",
                            "twofa",
                            "flowRevision",
                            1,
                            "testPointId",
                            "normal",
                            "changeEvidenceRefs",
                            List.of());
            post(url + "/scenarios", request, 400);
            post(
                    url + "/flows/twofa/1/decision",
                    Map.of("expectedHash", "stale", "accept", true),
                    400);
            var confirmed =
                    post(
                                    url + "/flows/twofa/1/decision",
                                    Map.of(
                                            "expectedHash",
                                            draft.get("hash").getAsString(),
                                            "accept",
                                            true),
                                    200)
                            .getAsJsonObject();
            assertEquals("isolated-fixture-reviewer", confirmed.get("confirmedBy").getAsString());
            var scenario = post(url + "/scenarios", request, 200).getAsJsonObject();
            String id = scenario.get("id").getAsString();
            assertEquals(1, get(url + "/scenarios").getAsJsonArray().size());
            var compilation =
                    post(url + "/scenarios/" + id + "/compile", server.profile(), 200)
                            .getAsJsonObject();
            assertTrue(compilation.get("jmx").getAsString().contains("JSR223Sampler"));
            assertEquals(
                    scenario.get("irHash").getAsString(), compilation.get("irHash").getAsString());
            if (System.getenv("JMETER_HOME") != null) {
                var run =
                        post(
                                        url + "/scenarios/" + id + "/runs",
                                        Map.of("profile", server.profile(), "timeoutSeconds", 30),
                                        202,"fixture-run")
                                .getAsJsonObject();
                var duplicate=post(url+"/scenarios/"+id+"/runs",Map.of("profile",server.profile(),"timeoutSeconds",30),202,"fixture-run").getAsJsonObject();
                assertEquals(run.get("id"),duplicate.get("id"));
                post(url+"/scenarios/"+id+"/runs",Map.of("profile",server.profile(),"timeoutSeconds",31),409,"fixture-run");
                assertEquals(1,get(url+"/runs").getAsJsonArray().size());
                long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
                while (Set.of("QUEUED", "RUNNING").contains(run.get("state").getAsString())
                        && System.nanoTime() < deadline) {
                    Thread.sleep(200);
                    run = get(url + "/runs/" + run.get("id").getAsString()).getAsJsonObject();
                }
                assertEquals("COMPLETED", run.get("state").getAsString(), run.toString());
                assertEquals("PASS", run.get("verdict").getAsString(), run.toString());
            }
        }
    }
}
