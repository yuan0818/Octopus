package io.yuan.octopus.service;
import com.github.javaparser.StaticJavaParser;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ModelSourceSanitizerTest {
    @Test void stripsCommentsAndConfigurationSecretsWhileKeepingHttpFieldNamesAndControlFlow() {
        var unit=StaticJavaParser.parse("""
            class Filter {
              // deployment-password-fixture
              @Value("${key:fixture-default-secret}") String key;
              @PostMapping("/login") String check(@RequestHeader("flToken") String token) {
                if (request.getHeader("requestId") == null) throw new Error("fixture-private-message");
                return "fixture-token-value";
              }
            }
            """);
        String result=ModelSourceSanitizer.sanitize(unit);
        for(String secret: new String[]{"deployment-password-fixture","fixture-default-secret","fixture-private-message","fixture-token-value"}) assertFalse(result.contains(secret));
        assertTrue(result.contains("flToken")); assertTrue(result.contains("requestId")); assertTrue(result.contains("/login")); assertTrue(result.contains("if ("));
    }
}
