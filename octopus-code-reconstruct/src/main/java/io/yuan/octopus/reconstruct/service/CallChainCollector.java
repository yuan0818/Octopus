package io.yuan.octopus.reconstruct.service;

import io.yuan.octopus.reconstruct.repository.CallChainRepository;
import io.yuan.octopus.reconstruct.repository.CallChainRepository.CalleeRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public CallChainCollector(CallChainRepository repo) {
        this.repo = repo;
    }

    public static class CallChainResult {
        public final String anchorClass;
        public final String anchorMethod;
        public final Set<String> involvedSignatures;
        public final Set<String> involvedClasses;
        public final Map<String, Set<String>> classToSigs;
        public final boolean truncated;
        /** Callee signature → caller signature, for shortest path reconstruction. */
        public final Map<String, String> parentMap;

        public CallChainResult(String anchorClass, String anchorMethod,
                                Set<String> involvedSignatures, Set<String> involvedClasses,
                                Map<String, Set<String>> classToSigs, boolean truncated,
                                Map<String, String> parentMap) {
            this.anchorClass = anchorClass;
            this.anchorMethod = anchorMethod;
            this.involvedSignatures = involvedSignatures;
            this.involvedClasses = involvedClasses;
            this.classToSigs = classToSigs;
            this.truncated = truncated;
            this.parentMap = parentMap;
        }

        // ── Accessors (compatible with record-style .method() calls) ──
        public String anchorClass() { return anchorClass; }
        public String anchorMethod() { return anchorMethod; }
        public Set<String> involvedSignatures() { return involvedSignatures; }
        public Set<String> involvedClasses() { return involvedClasses; }
        public Map<String, Set<String>> classToSigs() { return classToSigs; }
        public boolean truncated() { return truncated; }
        public Map<String, String> parentMap() { return parentMap; }
    }

    public enum Strategy { BFS, DFS }

    public CallChainResult collect(Long projectId, String className, String methodName, String strategy) {
        return collect(projectId, className, methodName, Strategy.valueOf(strategy != null ? strategy : "BFS"));
    }

    public CallChainResult collect(Long projectId, String className, String methodName, Strategy strategy) {
        Set<String> visitedSigs = new LinkedHashSet<>();
        Set<String> involvedClasses = new LinkedHashSet<>();
        Map<String, String> parentMap = new LinkedHashMap<>();

        // BFS: Queue, DFS: Stack
        Collection<TraversalItem> frontier = strategy == Strategy.DFS
                ? new ArrayDeque<>()
                : new LinkedList<>();

        frontier.add(new TraversalItem(className, methodName, 0));

        while (!frontier.isEmpty()) {
            TraversalItem current;
            if (strategy == Strategy.DFS) {
                current = ((ArrayDeque<TraversalItem>) frontier).removeLast();
            } else {
                current = ((Queue<TraversalItem>) frontier).poll();
            }

            List<CalleeRow> callees = repo.findDirectCallees(projectId, current.className, current.methodName);
            for (CalleeRow callee : callees) {
                if (visitedSigs.add(callee.signature())) {
                    parentMap.put(callee.signature(), current.signature);
                    involvedClasses.add(callee.className());
                    frontier.add(new TraversalItem(callee.className(), callee.methodName(), current.depth + 1));
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
        String anchorSig = repo.findSignature(projectId, className, methodName);
        if (anchorSig != null) {
            classToSigs.computeIfAbsent(className, k -> new LinkedHashSet<>()).add(anchorSig);
        }

        log.info("Call chain collected: {} signatures, {} classes",
                visitedSigs.size(), involvedClasses.size());

        return new CallChainResult(className, methodName, visitedSigs, involvedClasses, classToSigs, false, parentMap);
    }

    /**
     * Upstream (reverse) call chain traversal.
     * Starting from the target method, traverses CALLS relationships backward
     * to find all callers, until no more callers are found.
     */
    public CallChainResult collectUpstream(Long projectId, String className, String methodName, String strategy) {
        Strategy strat = strategy != null ? Strategy.valueOf(strategy) : Strategy.BFS;
        return collectUpstream(projectId, className, methodName, strat);
    }

    public CallChainResult collectUpstream(Long projectId, String className, String methodName, Strategy strategy) {
        Set<String> visitedSigs = new LinkedHashSet<>();
        Set<String> involvedClasses = new LinkedHashSet<>();
        Map<String, String> parentMap = new LinkedHashMap<>();

        Collection<TraversalItem> frontier = strategy == Strategy.DFS
                ? new ArrayDeque<>()
                : new LinkedList<>();

        frontier.add(new TraversalItem(className, methodName, 0));

        while (!frontier.isEmpty()) {
            TraversalItem current;
            if (strategy == Strategy.DFS) {
                current = ((ArrayDeque<TraversalItem>) frontier).removeLast();
            } else {
                current = ((Queue<TraversalItem>) frontier).poll();
            }

            List<CalleeRow> callers = repo.findDirectCallers(projectId, current.className, current.methodName);
            for (CalleeRow caller : callers) {
                if (visitedSigs.add(caller.signature())) {
                    parentMap.put(caller.signature(), current.signature);
                    involvedClasses.add(caller.className());
                    frontier.add(new TraversalItem(caller.className(), caller.methodName(), current.depth + 1));
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
        String anchorSig = repo.findSignature(projectId, className, methodName);
        if (anchorSig != null) {
            classToSigs.computeIfAbsent(className, k -> new LinkedHashSet<>()).add(anchorSig);
        }

        log.info("Upstream chain collected: {} signatures, {} classes",
                visitedSigs.size(), involvedClasses.size());

        return new CallChainResult(className, methodName, visitedSigs, involvedClasses, classToSigs, false, parentMap);
    }

    private record TraversalItem(String className, String methodName, String signature, int depth) {
        TraversalItem(String className, String methodName, int depth) {
            this(className, methodName, className + "." + methodName, depth);
        }
    }
}
