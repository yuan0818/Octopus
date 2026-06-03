package io.yuan.octopus.reconstruct.service;

import io.yuan.octopus.reconstruct.repository.CallChainRepository;
import io.yuan.octopus.reconstruct.repository.CallChainRepository.CalleeRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * BFS-based call chain collector.
 * Traverses Neo4j CALLS relationships downstream from an entry method
 * and returns all unique method signatures involved.  Data access is
 * delegated to {@link CallChainRepository}.
 */
@Component
public class CallChainCollector {

    private static final Logger log = LoggerFactory.getLogger(CallChainCollector.class);

    private final CallChainRepository repo;
    private final int defaultMaxDepth;

    public CallChainCollector(CallChainRepository repo,
                              @Value("${reconstruct.max-depth:10}") int defaultMaxDepth) {
        this.repo = repo;
        this.defaultMaxDepth = defaultMaxDepth;
    }

    public record CallChainResult(
        String anchorClass,
        String anchorMethod,
        Set<String> involvedSignatures,
        Set<String> involvedClasses,
        Map<String, Set<String>> classToSigs,
        boolean truncated
    ) {}

    public CallChainResult collect(String className, String methodName, int maxDepth) {
        int depth = maxDepth > 0 ? maxDepth : defaultMaxDepth;
        Set<String> visitedSigs = new LinkedHashSet<>();
        Set<String> involvedClasses = new LinkedHashSet<>();
        Queue<TraversalItem> queue = new LinkedList<>();

        queue.add(new TraversalItem(className, methodName, 0));

        while (!queue.isEmpty()) {
            TraversalItem current = queue.poll();
            if (current.depth >= depth) continue;

            List<CalleeRow> callees = repo.findDirectCallees(current.className, current.methodName);
            for (CalleeRow callee : callees) {
                if (visitedSigs.add(callee.signature())) {
                    involvedClasses.add(callee.className());
                    queue.add(new TraversalItem(callee.className(), callee.methodName(), current.depth + 1));
                }
            }
        }

        involvedClasses.add(className);

        // Group by class
        Map<String, Set<String>> classToSigs = new LinkedHashMap<>();
        for (String sig : visitedSigs) {
            String cls = CallChainRepository.extractClassName(sig);
            classToSigs.computeIfAbsent(cls, k -> new LinkedHashSet<>()).add(sig);
        }
        // Anchor method
        String anchorSig = repo.findSignature(className, methodName);
        if (anchorSig != null) {
            classToSigs.computeIfAbsent(className, k -> new LinkedHashSet<>()).add(anchorSig);
        }

        log.info("Call chain collected: {} signatures, {} classes (depth={})",
                visitedSigs.size(), involvedClasses.size(), depth);

        return new CallChainResult(className, methodName, visitedSigs, involvedClasses, classToSigs, false);
    }

    private record TraversalItem(String className, String methodName, int depth) {}
}
