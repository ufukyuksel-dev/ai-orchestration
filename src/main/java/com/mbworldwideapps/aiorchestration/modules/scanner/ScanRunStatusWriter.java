package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ScanRunStatusWriter {

    private final CodeBaselineRepository codeBaselineRepository;
    private final TransactionTemplate requiresNew;

    @Autowired
    public ScanRunStatusWriter(CodeBaselineRepository codeBaselineRepository,
            PlatformTransactionManager transactionManager) {
        this.codeBaselineRepository = codeBaselineRepository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    ScanRunStatusWriter(CodeBaselineRepository codeBaselineRepository) {
        this.codeBaselineRepository = codeBaselineRepository;
        this.requiresNew = null;
    }

    public void completeFailedRun(UUID scanRunId, int filesDiscovered, int filesScanned, int filesSkipped,
            int filesRejected, int candidatesCreated, Map<String, Object> metadata) {
        Runnable write = () -> codeBaselineRepository.completeRun(scanRunId, "failed", filesDiscovered,
                filesScanned, filesSkipped, filesRejected, candidatesCreated, metadata);
        if (requiresNew == null) {
            write.run();
            return;
        }
        requiresNew.executeWithoutResult(status -> write.run());
    }
}
