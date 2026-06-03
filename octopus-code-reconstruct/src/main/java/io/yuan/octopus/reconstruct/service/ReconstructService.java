package io.yuan.octopus.reconstruct.service;

import io.yuan.octopus.reconstruct.dto.ReconstructRequest;
import io.yuan.octopus.reconstruct.dto.ReconstructResponse;
import io.yuan.octopus.reconstruct.service.CallChainCollector.CallChainResult;
import io.yuan.octopus.reconstruct.service.ClassSourceAssembler.AssembledClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Orchestrates the full reconstruct pipeline:
 * 1. Collect call chain via {@link CallChainCollector}
 * 2. Assemble source per class via {@link ClassSourceAssembler}
 * 3. Build response
 */
@Service
public class ReconstructService {

    private static final Logger log = LoggerFactory.getLogger(ReconstructService.class);

    private final CallChainCollector chainCollector;
    private final ClassSourceAssembler sourceAssembler;
    private final int maxClasses;

    public ReconstructService(CallChainCollector chainCollector,
                              ClassSourceAssembler sourceAssembler,
                              @Value("${reconstruct.max-classes:50}") int maxClasses) {
        this.chainCollector = chainCollector;
        this.sourceAssembler = sourceAssembler;
        this.maxClasses = maxClasses;
    }

    public ReconstructResponse reconstruct(ReconstructRequest request) {
        long start = System.currentTimeMillis();

        // Step 1: collect downstream call chain
        CallChainResult chain = chainCollector.collect(
            request.className(), request.methodName(), request.getEffectiveDepth());

        if (chain.involvedSignatures().isEmpty() && chain.involvedClasses().size() <= 1) {
            throw new NoSuchElementException("No downstream methods found for "
                + request.className() + "." + request.methodName());
        }

        // Step 2: assemble source for each class
        Map<String, String> classSources = new LinkedHashMap<>();
        boolean truncated = false;
        int totalMethods = 0;
        int totalChars = 0;

        List<String> classesToProcess = new ArrayList<>(chain.classToSigs().keySet());
        // Anchor first
        if (chain.classToSigs().containsKey(request.className())) {
            classesToProcess.remove(request.className());
            classesToProcess.add(0, request.className());
        }

        int processed = 0;
        for (String clsName : classesToProcess) {
            if (processed >= maxClasses) { truncated = true; break; }
            Set<String> sigs = chain.classToSigs().get(clsName);
            if (sigs == null || sigs.isEmpty()) continue;

            try {
                AssembledClass assembled = sourceAssembler.assemble(clsName, sigs);
                if (assembled != null) {
                    classSources.put(clsName, assembled.sourceCode());
                    totalMethods += sigs.size();
                    totalChars += assembled.sourceCode().length();
                    processed++;
                }
            } catch (Exception e) {
                log.warn("Failed to assemble class {}: {}", clsName, e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Reconstructed {} classes / {} methods in {}ms for {}.{}",
            classSources.size(), totalMethods, elapsed,
            request.className(), request.methodName());

        return new ReconstructResponse(
            request.className() + "." + request.methodName(),
            classSources,
            new ArrayList<>(classSources.keySet()),
            totalMethods,
            totalChars,
            truncated
        );
    }
}
