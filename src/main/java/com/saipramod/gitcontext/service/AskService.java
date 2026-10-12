package com.saipramod.gitcontext.service;

import com.saipramod.gitcontext.answer.Answer;
import com.saipramod.gitcontext.answer.AnswerGenerator;
import com.saipramod.gitcontext.answer.CitationValidator;
import com.saipramod.gitcontext.retrieve.RetrievalMode;
import com.saipramod.gitcontext.retrieve.RetrievedChunk;

import java.util.ArrayList;
import java.util.List;

/** Retrieve -> guard -> generate -> verify citations. */
public class AskService {

    /** Calibrated on the dev half of eval/golden.jsonl; see README. */
    public static final double DEFAULT_MIN_EVIDENCE = 0.45;

    private final CodebaseIndex index;
    private final AnswerGenerator generator;
    private final double minEvidence;

    /** @param minEvidence below this {@link CodebaseIndex#evidenceScore} the service abstains */
    public AskService(CodebaseIndex index, AnswerGenerator generator, double minEvidence) {
        this.index = index;
        this.generator = generator;
        this.minEvidence = minEvidence;
    }

    public Answer ask(String question, int k, RetrievalMode mode) {
        long t0 = System.nanoTime();
        boolean evidence = index.hasEvidence(question, minEvidence);
        List<RetrievedChunk> context = evidence ? index.search(question, k, mode) : List.of();
        long retrievalMs = (System.nanoTime() - t0) / 1_000_000;

        if (context.isEmpty()) {
            return new Answer(question, "I couldn't find this in the indexed repository. Try naming a class, "
                    + "file, config key or error message.", true, false, List.of(), List.of(), mode.name(),
                    generator.name(), retrievalMs, 0);
        }

        long t1 = System.nanoTime();
        String text;
        try {
            text = generator.generate(question, context);
        } catch (RuntimeException e) {
            text = "The answer generator failed (" + e.getClass().getSimpleName()
                    + "). Showing the retrieved sources only.";
        }
        long generationMs = (System.nanoTime() - t1) / 1_000_000;

        CitationValidator.Result citations = CitationValidator.validate(text, context.size());
        List<Answer.Source> sources = new ArrayList<>();
        for (int i = 0; i < context.size(); i++) {
            RetrievedChunk rc = context.get(i);
            var c = rc.chunk();
            sources.add(new Answer.Source(i + 1, c.location(), c.kind().name(), c.symbol(),
                    Math.round(rc.score() * 10_000) / 10_000.0,
                    c.metadata().get("commit"), c.metadata().get("author"), c.metadata().get("lastModified"),
                    citations.cited().contains(i + 1)));
        }
        return new Answer(question, text, false, citations.grounded(), citations.invalid(), sources, mode.name(),
                generator.name(), retrievalMs, generationMs);
    }
}
