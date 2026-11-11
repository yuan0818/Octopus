package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-POLL: per-API poll authorizations. Created, changed and revoked only by explicit user action on a single
 * API; nothing here infers pollability. Flows pin {@code id@revision}; runs and confirmations re-check it.
 */
@Service
public class PollAuthorizationService {
    public record Request(long projectId, String httpMethod, String path, String signature,
                          long intervalMillis, int maxAttempts, long timeoutMillis, String reason) {}

    private final PollAuthorizationRepository repository;

    public PollAuthorizationService(PollAuthorizationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public PollAuthorization authorize(Request request, String user) {
        require(request != null && request.projectId() > 0, "请选择具体 API");
        String method = request.httpMethod() == null ? "" : request.httpMethod().toUpperCase(Locale.ROOT);
        require(Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(method), "HTTP 方法无效");
        require(request.path() != null && request.path().startsWith("/") && request.path().length() <= 512, "路径无效");
        require(request.signature() != null && !request.signature().isBlank(), "缺少方法签名");
        require(request.reason() != null && !request.reason().isBlank() && request.reason().length() <= 500, "请填写授权理由（≤500 字符）");
        String bounds = PollAuthorizationRules.invalidBounds(request.intervalMillis(), request.maxAttempts(), request.timeoutMillis());
        require(bounds == null, bounds);
        String signature = canonical(request.signature());
        var existing = active(request.projectId(), method, request.path(), signature);
        var now = Instant.now();
        PollAuthorization a = existing.orElseGet(() -> {
            var created = new PollAuthorization();
            created.id = UUID.randomUUID().toString();
            created.projectId = request.projectId();
            created.httpMethod = method;
            created.path = request.path();
            created.signature = signature;
            created.revision = 0;
            created.createdBy = user;
            created.createdAt = now;
            return created;
        });
        a.revision++;
        a.intervalMillis = request.intervalMillis();
        a.maxAttempts = request.maxAttempts();
        a.timeoutMillis = request.timeoutMillis();
        a.reason = request.reason().strip();
        a.updatedBy = user;
        a.updatedAt = now;
        return repository.save(a);
    }

    @Transactional
    public PollAuthorization revoke(String id, String user) {
        var a = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("授权不存在"));
        require(!a.revoked, "授权已撤销");
        a.revoked = true;
        a.revision++;
        a.updatedBy = user;
        a.updatedAt = Instant.now();
        return repository.save(a);
    }

    public List<PollAuthorization> list() {
        return repository.findAllByOrderByUpdatedAtDesc();
    }

    /** The active authorization for an operation's single pinned implementation, if any. */
    public Optional<PollAuthorization> forOperation(Operation operation) {
        if (operation == null || operation.implementations() == null || operation.implementations().size() != 1) return Optional.empty();
        var impl = operation.implementations().getFirst();
        return active(impl.projectId(), operation.method(), operation.path(), canonical(impl.signature()));
    }

    public static PollAuthorizationRules.Grant grant(PollAuthorization a) {
        return a == null ? null : new PollAuthorizationRules.Grant(a.id, a.revision, a.intervalMillis, a.maxAttempts, a.timeoutMillis);
    }

    /** Problem with one call's poll, or null when it is authorized. */
    public String problem(Poll poll, Operation operation) {
        if (poll == null) return null;
        return PollAuthorizationRules.problem(poll.intervalMillis(), poll.timeoutMillis(), poll.authorizationRef(), grant(forOperation(operation).orElse(null)));
    }

    /** FR-POLL-04/06: before a run, every polling step must be covered; otherwise nothing is sent. */
    public void verify(TestIR ir, Function<String, Operation> operations) {
        var calls = new ArrayList<>(ir.steps());
        if (ir.cleanup() != null) ir.cleanup().forEach(c -> calls.add(c.call()));
        for (var step : calls) {
            if (step.poll() == null) continue;
            String problem = problem(step.poll(), operations.apply(step.operationRef()));
            require(problem == null, "轮询授权失效（" + step.id() + "）：" + problem);
        }
    }

    /** Confirmation check for flows (canvas and DSL alike, FR-POLL-06). */
    public void verify(Flow flow, Function<String, Operation> operations) {
        for (var node : flow.nodes()) {
            if (node.poll() == null) continue;
            String problem = problem(node.poll(), operations.apply(node.operationRef()));
            require(problem == null, "轮询授权无效（" + node.id() + "）：" + problem);
        }
    }

    private Optional<PollAuthorization> active(long projectId, String method, String path, String signature) {
        return repository.findByProjectIdAndHttpMethodAndPathAndRevokedFalse(projectId, method, path).stream()
                .filter(a -> a.signature.equals(signature)).findFirst();
    }

    static String canonical(String signature) {
        return signature == null ? "" : signature.replaceAll("\\s+", "");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
