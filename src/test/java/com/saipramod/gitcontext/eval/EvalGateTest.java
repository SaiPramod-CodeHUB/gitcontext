package com.saipramod.gitcontext.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quality gate: CI fails if a change drops retrieval quality below the agreed floor.
 * Runs the same golden set as {@code mvn exec:java} against this repository.
 */
class EvalGateTest {

    @Test
    void retrievalQualityDoesNotRegress() throws Exception {
        EvalRunner.Report r = EvalRunner.run(Path.of("."), Path.of("eval/golden.jsonl"));
        EvalRunner.ModeResult best = r.modes().stream()
                .filter(m -> m.mode().equals("HYBRID_RERANK")).findFirst().orElseThrow();

        assertThat(best.recall5()).as("Recall@5").isGreaterThanOrEqualTo(0.85);
        assertThat(best.mrr()).as("MRR").isGreaterThanOrEqualTo(0.70);
        assertThat(r.falseAbstainOnPositives()).as("false abstain").isLessThanOrEqualTo(0.10);
        assertThat(r.abstainOnNegatives()).as("abstain on out-of-scope").isGreaterThanOrEqualTo(0.60);
    }
}
