package io.yuan.octopus.scenario.testing;

import static org.junit.jupiter.api.Assertions.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import io.yuan.octopus.preparation.PreparationPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Actual JMeter subprocess -> local Gateway verifier -> RSA/AES decrypt -> token propagation. */
@EnabledIfEnvironmentVariable(named="JMETER_HOME",matches=".+")
class PreparationJmeterIntegrationTest {
    @TempDir Path directory;
    HttpServer server; KeyPair keys; String algorithm="MD5";
    final ObjectMapper json=new ObjectMapper();
    final AtomicInteger calls=new AtomicInteger(), downstream=new AtomicInteger();
    final List<String> ciphertexts=new ArrayList<>();
    final AtomicReference<Throwable> serverFailure=new AtomicReference<>();
    final String secret="fixture-only-signing-secret";
    final Map<String,Object> plaintext=Map.of("email","local-fixture@example.invalid","password","fixture-password ${__groovy(throw new Exception())}","metadata",Map.of("z","末尾","a",1));
    @BeforeEach void start() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); keys=generator.generateKeyPair();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/login",exchange -> {
            calls.incrementAndGet(); int status=200; String response="{\"token\":\"fixture-token\"}";
            try {
                var body=json.readValue(exchange.getRequestBody().readAllBytes(),LinkedHashMap.class);
                assertFalse(body.containsKey("content")); assertFalse(body.containsKey("password"));
                ciphertexts.add((String)body.get("cipher"));
                var signContent=new TreeMap<String,Object>();
                for(String name:List.of("requestid","appid","timestamp")) signContent.put(name,exchange.getRequestHeaders().getFirst(name));
                signContent.putAll(cn.hutool.http.HttpUtil.decodeParamMap(exchange.getRequestURI().getQuery(),StandardCharsets.UTF_8));
                signContent.putAll(body);
                var canonical=new StringBuilder();
                for(var entry:signContent.entrySet()) canonical.append(entry.getKey()).append('=').append(entry.getValue()).append('&');
                canonical.append("key=").append(secret);
                String expected=HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
                assertEquals(expected,exchange.getRequestHeaders().getFirst("sign"));
                assertEquals(String.valueOf(body.get("timestamp")),exchange.getRequestHeaders().getFirst("timestamp"));
                var rsa=Cipher.getInstance("RSA/ECB/PKCS1Padding"); rsa.init(Cipher.DECRYPT_MODE,keys.getPrivate());
                byte[] key=rsa.doFinal(Base64.getDecoder().decode((String)body.get("cipher")));
                var aes=Cipher.getInstance("AES/ECB/PKCS5Padding"); aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"));
                assertEquals(plaintext,json.readValue(aes.doFinal(Base64.getDecoder().decode((String)body.get("data"))),Map.class));
            } catch(Throwable e) { serverFailure.set(e); status=400; response="{}"; }
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/verify",exchange -> {
            downstream.incrementAndGet();
            if(!"Bearer fixture-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) serverFailure.set(new AssertionError("Token propagation failed"));
            byte[] bytes="{\"ok\":true}".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    JmeterProcessExecutor.Result run(String name,PreparationPlan plan,String publicKey) throws Exception {
        var registry=new CapabilityRegistry();
        var ir=PreparationFixtures.ir(plan);
        var profile=new ExecutionProfile(Map.of("account.auth",new Target("http://127.0.0.1:"+server.getAddress().getPort())),
            Map.of("credentials",plaintext,"rsa.public",publicKey,"signing.secret",secret),Map.of());
        var compilation=new JmxCompiler(new IrValidator(registry),registry).compile(ir,profile);
        assertFalse(compilation.jmx().contains(secret)); assertFalse(compilation.jmx().contains(publicKey));
        Path output=directory.resolve(name);
        var result=new JmeterProcessExecutor().execute(Path.of(System.getenv("JMETER_HOME")),output,compilation,profile,60,new AtomicBoolean());
        assertFalse(Files.exists(output.resolve("context.json"))); assertFalse(Files.exists(output.resolve("connections.json")));
        try(var files=Files.list(output)) {
            for(var file:files.toList()) {
                String content=Files.readString(file);
                assertFalse(content.contains(secret),"Credential leaked into "+file.getFileName());
                assertFalse(content.contains("fixture-password"),"Plaintext leaked into "+file.getFileName());
            }
        }
        return result;
    }
    String publicKey() { return Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()); }
    @Test void sendsValidEncryptedSignedRequestsAndPassesTokenWithFreshMaterialOnRerun() throws Exception {
        for(String hash:List.of("MD5","SHA-256")) {
            algorithm=hash;
            var result=run(hash,PreparationFixtures.plan(hash),publicKey());
            assertNull(serverFailure.get(),String.valueOf(serverFailure.get()));
            assertEquals("PASS",result.verdict(),TestingJson.canonical(result));
            assertEquals(6,result.samples().stream().filter(s -> s.label().startsWith("prepare:")).count());
        }
        assertEquals(2,calls.get()); assertEquals(2,downstream.get()); assertNotEquals(ciphertexts.get(0),ciphertexts.get(1));
    }
    @Test void badKeyAndMissingCredentialStopBeforeNetworkAndAreInconclusive() throws Exception {
        var bad=run("bad-key",PreparationFixtures.plan("MD5"),"invalid-public-key");
        assertEquals("INCONCLUSIVE",bad.verdict(),TestingJson.canonical(bad));
        assertTrue(bad.samples().stream().anyMatch(s -> s.responseCode().equals("PREPARE_PUBLIC_KEY_INVALID")),TestingJson.canonical(bad));
        var plan=PreparationFixtures.plan("MD5"); var calls=new ArrayList<>(plan.tools()); var call=calls.get(4);
        var inputs=new HashMap<>(call.inputs()); inputs.put("signingSecret",new PreparationPlan.Value("secret","missing.reference"));
        calls.set(4,new PreparationPlan.Call(call.id(),call.tool(),call.version(),inputs));
        var missing=run("missing-secret",new PreparationPlan(calls,plan.body(),plan.headers()),publicKey());
        assertEquals("INCONCLUSIVE",missing.verdict());
        assertTrue(missing.samples().stream().anyMatch(s -> s.responseCode().equals("PREPARE_CREDENTIAL_MISSING")),TestingJson.canonical(missing));
        assertEquals(0,this.calls.get()); assertEquals(0,downstream.get());
    }
}
