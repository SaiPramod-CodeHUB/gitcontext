package com.saipramod.gitcontext.retrieve;

import com.saipramod.gitcontext.core.Chunk;
import com.saipramod.gitcontext.core.ChunkKind;
import com.saipramod.gitcontext.index.Tokenizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feature-based cross-scorer that runs offline. It looks at signals a first-stage index
 * cannot combine: where the query terms hit (symbol name vs. path vs. body), whether an
 * exact error string / quoted phrase appears verbatim, term proximity, and whether the
 * question is about code ("where is X computed") or about intent ("why did we...").
 * Weights are hand-set (no learned model), so treat eval gains from this stage with care.
 * Replace with {@link LlmReranker} or a cross-encoder model for production.
 */
public class HeuristicReranker implements Reranker {

    private static final Pattern QUOTED = Pattern.compile("[\"'`]([^\"'`]{6,})[\"'`]");
    private java.util.function.ToDoubleFunction<String> idf = t -> 1.0;

    @Override
    public void attach(com.saipramod.gitcontext.index.Bm25Index index) {
        this.idf = index::idf;
    }

    private static final Set<String> WHY_WORDS = Set.of("why", "decision", "incident", "outage", "postmortem",
            "rationale", "history", "changed", "design", "architecture", "overview", "readme", "doc", "setup", "run");

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates) {
        List<String> qTokens = Tokenizer.tokenize(query).stream().distinct().toList();
        String qLower = query.toLowerCase(Locale.ROOT);
        boolean whyQuestion = WHY_WORDS.stream().anyMatch(qLower::contains);
        String quoted = quoted(query);
        double maxFirst = candidates.stream().mapToDouble(RetrievedChunk::score).max().orElse(1);

        List<RetrievedChunk> out = new ArrayList<>(candidates.size());
        for (RetrievedChunk rc : candidates) {
            Chunk c = rc.chunk();
            List<String> bodyTokens = Tokenizer.tokenize(c.text());
            Set<String> body = new HashSet<>(bodyTokens);
            Set<String> symbol = new HashSet<>(Tokenizer.tokenize(c.symbol() == null ? "" : c.symbol()));
            Set<String> path = new HashSet<>(Tokenizer.tokenize(c.path()));

            double coverage = weighted(qTokens, body);
            double symbolHit = weighted(qTokens, symbol);
            double pathHit = weighted(qTokens, path);
            boolean testFile = c.path().contains("/test/") && !qLower.contains("test");
            double proximity = proximity(qTokens, bodyTokens);
            double exact = quoted != null && c.text().contains(quoted) ? 1 : 0;
            double first = rc.score() / Math.max(1e-9, maxFirst);
            double kindPrior = kindPrior(c.kind(), whyQuestion);
            double lengthPenalty = Math.min(1, Math.max(0, (c.endLine() - c.startLine() - 60) / 200.0));

            double score = 0.45 * first
                    + 0.30 * coverage
                    + 0.15 * symbolHit
                    + 0.05 * pathHit
                    + 0.10 * proximity
                    + 0.60 * exact
                    + kindPrior
                    - 0.10 * lengthPenalty
                    - (testFile ? 0.08 : 0);
            out.add(rc.withScore(score));
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out;
    }

    private static double kindPrior(ChunkKind kind, boolean whyQuestion) {
        return switch (kind) {
            case DOC_SECTION, INCIDENT, COMMIT -> whyQuestion ? 0.12 : -0.04;
            case METHOD -> whyQuestion ? 0.0 : 0.05;
            case CLASS -> 0.02;
            default -> 0.0;
        };
    }

    /** 1.0 when all query terms that occur appear within a short window, 0 when spread out. */
    static double proximity(List<String> qTokens, List<String> body) {
        Set<String> wanted = new HashSet<>(qTokens);
        wanted.retainAll(new HashSet<>(body));
        if (wanted.size() < 2) {
            return 0;
        }
        int best = Integer.MAX_VALUE;
        java.util.Map<String, Integer> lastSeen = new java.util.HashMap<>();
        for (int i = 0; i < body.size(); i++) {
            String t = body.get(i);
            if (wanted.contains(t)) {
                lastSeen.put(t, i);
                if (lastSeen.size() == wanted.size()) {
                    int min = lastSeen.values().stream().mapToInt(Integer::intValue).min().orElse(i);
                    best = Math.min(best, i - min + 1);
                }
            }
        }
        if (best == Integer.MAX_VALUE) {
            return 0;
        }
        return Math.min(1.0, (double) wanted.size() * 3 / best);
    }

    /** Share of the query's idf mass found in the target: matching a rare term counts more than a common one. */
    private double weighted(List<String> q, Set<String> target) {
        double total = q.stream().mapToDouble(idf).sum();
        if (total == 0) {
            return 0;
        }
        return q.stream().filter(target::contains).mapToDouble(idf).sum() / total;
    }

    private static String quoted(String query) {
        Matcher m = QUOTED.matcher(query);
        return m.find() ? m.group(1) : null;
    }

    @Override
    public String name() {
        return "heuristic-cross-scorer";
    }
}
