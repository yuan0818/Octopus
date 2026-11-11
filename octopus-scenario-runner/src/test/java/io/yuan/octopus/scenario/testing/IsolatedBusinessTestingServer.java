package io.yuan.octopus.scenario.testing;

import com.sun.net.httpserver.HttpServer;

import io.yuan.octopus.scenario.ScenarioRunnerApplication;

import org.neo4j.harness.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;

/** Disposable integration environment. Production entrypoint never loads this test class. */
public final class IsolatedBusinessTestingServer implements AutoCloseable {
    final Neo4j graph;
    final HttpServer fixture;
    final ConfigurableApplicationContext application;
    final String baseUrl;

    IsolatedBusinessTestingServer(int port) throws Exception {
        graph = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext(
                "/login",
                e -> {
                    byte[] body = "{\"token\":\"fixture-token\"}".getBytes();
                    e.sendResponseHeaders(200, body.length);
                    e.getResponseBody().write(body);
                    e.close();
                });
        fixture.createContext(
                "/verify",
                e -> {
                    boolean valid =
                            "Bearer fixture-token"
                                    .equals(e.getRequestHeaders().getFirst("Authorization"));
                    byte[] body = ("{\"ok\":" + valid + "}").getBytes();
                    e.sendResponseHeaders(valid ? 200 : 401, body.length);
                    e.getResponseBody().write(body);
                    e.close();
                });
        fixture.start();
        application =
                new SpringApplicationBuilder(ScenarioRunnerApplication.class)
                        .run(
                                "--server.address=127.0.0.1",
                                "--server.port=" + port,
                                "--spring.datasource.url=jdbc:h2:mem:bt_"
                                        + UUID.randomUUID()
                                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                                "--spring.datasource.driver-class-name=org.h2.Driver",
                                "--spring.datasource.username=sa",
                                "--spring.datasource.password=",
                                "--spring.jpa.hibernate.ddl-auto=none",
                                "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                                "--spring.sql.init.mode=always",
                                "--spring.sql.init.schema-locations=classpath:db/business-testing.sql",
                                "--octopus.neo4j.uri=" + graph.boltURI(),
                                "--octopus.neo4j.username=fixture",
                                "--octopus.neo4j.password=fixture",
                                "--scenario.testing.reviewer=isolated-fixture-reviewer",
                                "--scenario.testing.artifact-dir="
                                        + Files.createTempDirectory("octopus-bt-http-"),
                                "--logging.level.root=WARN");
        baseUrl =
                "http://127.0.0.1:"
                        + application.getEnvironment().getProperty("local.server.port")
                        + "/api/business-testing";
    }

    TestingModel.ExecutionProfile profile() {
        return new TestingModel.ExecutionProfile(
                Map.of(
                        "account.auth",
                        new TestingModel.Target(
                                "http://127.0.0.1:" + fixture.getAddress().getPort())),
                Map.of("credentials", Map.of("user", "fixture")),
                Map.of());
    }

    void seed() {
        var store = application.getBean(BusinessKnowledgeStore.class);
        store.saveOperation(TestingFixtures.login());
        store.saveOperation(TestingFixtures.verify());
        store.saveDraft(TestingFixtures.flow());
    }

    @Override
    public void close() {
        application.close();
        fixture.stop(0);
        graph.close();
    }

    public static void main(String[] args) throws Exception {
        var server = new IsolatedBusinessTestingServer(18086);
        server.seed();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println(
                "ISOLATED_BUSINESS_TESTING_READY "
                        + server.baseUrl
                        + " fixtureProfile="
                        + TestingJson.canonical(server.profile()));
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
