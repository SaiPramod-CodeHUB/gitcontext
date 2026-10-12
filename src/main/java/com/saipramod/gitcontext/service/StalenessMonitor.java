package com.saipramod.gitcontext.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically detects files whose content hash changed and re-indexes only those. */
@Component
@ConditionalOnExpression("${gitcontext.staleness-check-ms:0} > 0")
public class StalenessMonitor {

    private static final Logger log = LoggerFactory.getLogger(StalenessMonitor.class);
    private final CodebaseIndex index;

    public StalenessMonitor(CodebaseIndex index) {
        this.index = index;
    }

    @Scheduled(fixedDelayString = "${gitcontext.staleness-check-ms}",
            initialDelayString = "${gitcontext.staleness-check-ms}")
    public void check() {
        if (index.root() == null) {
            return;
        }
        CodebaseIndex.ReindexReport report = index.reindexChanged();
        if (!report.noop()) {
            log.info("Stale index refreshed in {} ms: changed={} added={} deleted={} chunks {} -> {}",
                    report.millis(), report.changed(), report.added(), report.deleted(),
                    report.chunksBefore(), report.chunksAfter());
        }
    }
}
