package com.saipramod.gitcontext.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.saipramod.gitcontext.answer.Answer;
import com.saipramod.gitcontext.answer.ExtractiveAnswerGenerator;
import com.saipramod.gitcontext.index.HashingEmbedder;
import com.saipramod.gitcontext.retrieve.HeuristicReranker;
import com.saipramod.gitcontext.retrieve.RetrievalMode;
import com.saipramod.gitcontext.retrieve.RetrievedChunk;
import com.saipramod.gitcontext.service.AskService;
import com.saipramod.gitcontext.service.CodebaseIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Offline retrieval evaluation over a golden set of (question -> relevant files).
 *
 * <pre>mvn -q compile exec:java                       # defaults: repo ".", eval/golden.jsonl
 * mvn -q compile exec:java -Dexec.args="/path/to/repo my-golden.jsonl out-dir"</pre>
 *
 * Metrics per retrieval mode: Recall@1/3/5/10 (hit rate), MRR@10, Precision@5 (share of the
 * top-5 chunks that come from a relevant file), citation accuracy (share of answer citations
 * pointing to a relevant file), abstention on out-of-scope questions, and retrieval latency.
 */
public final class EvalRunner {

    /** split: "dev" (used while tuning reranker weights / guard thresholds) or "test" (held out). */
    public record GoldenQuery(String q, Set<String> relevant, String type, String split) {
        boolean negative() {
            return relevant.isEmpty();
        }
    }

    public record ModeResult(String mode, int queries, double recall1, double recall3, double recall5,
                             double recall10, double mrr, double precision5, double citationAccuracy,
                             double p50Ms, double p95Ms, Map<String, Double> recall5ByType) { }

    public record Report(String corpus, int files, int chunks, String embedder, String reranker,
                         int positives, int negatives, double abstainOnNegatives, double falseAbstainOnPositives,
                         List<ModeResult> modes, List<ModeResult> heldOutModes, List<String> misses) { }

    private EvalRunner() { }

    public static void main(String[] args) throws IOException {
        Path repo = Path.of(args.length > 0 ? args[0] : ".");
        Path golden = Path.of(args.length > 1 ? args[1] : "eval/golden.jsonl");
        Path out = Path.of(args.length > 2 ? args[2] : "eval/results");
        Report report = run(repo, golden);
        write(report, out);
        System.out.println(markdown(report));
    }

    public static Report run(Path repo, Path goldenFile) throws IOException {
        List<GoldenQuery> golden = load(goldenFile);
        // The eval folder holds the questions themselves; indexing it would leak answers.
        CodebaseIndex index = new CodebaseIndex(new HashingEmbedder(), new HeuristicReranker(), true, Set.of("eval"));
        CodebaseIndex.Stats stats = index.index(repo);
        AskService ask = new AskService(index, new ExtractiveAnswerGenerator(),
                AskService.DEFAULT_MIN_EVIDENCE);

        // warm-up so JIT does not skew latency of the first mode
        golden.stream().limit(5).forEach(g -> index.search(g.q(), 10, RetrievalMode.HYBRID_RERANK));

        List<GoldenQuery> positives = golden.stream().filter(g -> !g.negative()).toList();
        List<GoldenQuery> negatives = golden.stream().filter(GoldenQuery::negative).toList();
        List<GoldenQuery> heldOut = positives.stream().filter(g -> g.split().equals("test")).toList();
        List<ModeResult> modes = new ArrayList<>();
        List<ModeResult> heldOutModes = new ArrayList<>();
        List<String> misses = new ArrayList<>();
        for (RetrievalMode mode : RetrievalMode.values()) {
            modes.add(evaluate(index, ask, positives, mode, mode == RetrievalMode.HYBRID_RERANK ? misses : null));
            heldOutModes.add(evaluate(index, ask, heldOut, mode, null));
        }
        if (Boolean.getBoolean("calibrate")) {
            golden.forEach(g -> {
                double[] sig = index.evidenceSignals(g.q());
                System.out.printf(Locale.ROOT, "%-9s %-4s coverage=%.2f cosine=%.2f  %s%n",
                        g.type(), g.split(), sig[0], sig[1], g.q());
            });
        }

        long abstained = negatives.stream().filter(g -> ask.ask(g.q(), 5, RetrievalMode.HYBRID_RERANK).abstained()).count();
        long falseAbstain = positives.stream().filter(g -> ask.ask(g.q(), 5, RetrievalMode.HYBRID_RERANK).abstained()).count();
        return new Report(stats.root(), stats.files(), stats.chunks(), stats.embedder(), stats.reranker(),
                positives.size(), negatives.size(),
                negatives.isEmpty() ? 0 : (double) abstained / negatives.size(),
                positives.isEmpty() ? 0 : (double) falseAbstain / positives.size(),
                modes, heldOutModes, misses);
    }

    private static ModeResult evaluate(CodebaseIndex index, AskService ask, List<GoldenQuery> queries,
                                       RetrievalMode mode, List<String> misses) {
        double r1 = 0, r3 = 0, r5 = 0, r10 = 0, mrr = 0, p5 = 0;
        int cited = 0, citedCorrect = 0;
        List<Double> latencies = new ArrayList<>();
        Map<String, int[]> byType = new LinkedHashMap<>();
        for (GoldenQuery g : queries) {
            long t0 = System.nanoTime();
            List<RetrievedChunk> hits = index.search(g.q(), 10, mode);
            latencies.add((System.nanoTime() - t0) / 1e6);

            int firstRelevant = 0;
            int relevantInTop5 = 0;
            for (int i = 0; i < hits.size(); i++) {
                boolean rel = g.relevant().contains(hits.get(i).chunk().path());
                if (rel && firstRelevant == 0) {
                    firstRelevant = i + 1;
                }
                if (rel && i < 5) {
                    relevantInTop5++;
                }
            }
            r1 += hit(firstRelevant, 1);
            r3 += hit(firstRelevant, 3);
            r5 += hit(firstRelevant, 5);
            r10 += hit(firstRelevant, 10);
            mrr += firstRelevant == 0 ? 0 : 1.0 / firstRelevant;
            p5 += Math.min(5, hits.size()) == 0 ? 0 : (double) relevantInTop5 / Math.min(5, hits.size());
            int[] t = byType.computeIfAbsent(g.type(), k -> new int[2]);
            t[0]++;
            t[1] += (int) hit(firstRelevant, 5);

            Answer a = ask.ask(g.q(), 5, mode);
            for (Answer.Source s : a.sources()) {
                if (s.cited()) {
                    cited++;
                    String path = s.location().contains(":L") ? s.location().substring(0, s.location().lastIndexOf(":L")) : s.location();
                    if (g.relevant().contains(path)) {
                        citedCorrect++;
                    }
                }
            }
            if (misses != null && (firstRelevant == 0 || firstRelevant > 5)) {
                misses.add(String.format("%s -> expected %s, got %s", g.q(), g.relevant(),
                        hits.stream().limit(3).map(h -> h.chunk().path()).toList()));
            }
        }
        int n = Math.max(1, queries.size());
        Map<String, Double> r5ByType = new LinkedHashMap<>();
        byType.forEach((type, t) -> r5ByType.put(type, round((double) t[1] / t[0])));
        latencies.sort(null);
        return new ModeResult(mode.name(), queries.size(), round(r1 / n), round(r3 / n), round(r5 / n), round(r10 / n),
                round(mrr / n), round(p5 / n), cited == 0 ? 0 : round((double) citedCorrect / cited),
                round(percentile(latencies, 50)), round(percentile(latencies, 95)), r5ByType);
    }

    static double hit(int firstRelevant, int k) {
        return firstRelevant > 0 && firstRelevant <= k ? 1 : 0;
    }

    static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    public static List<GoldenQuery> load(Path file) throws IOException {
        ObjectMapper om = new ObjectMapper();
        List<GoldenQuery> out = new ArrayList<>();
        int pos = 0;
        int neg = 0;
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank() || line.startsWith("//")) {
                continue;
            }
            JsonNode n = om.readTree(line);
            Set<String> rel = new java.util.LinkedHashSet<>();
            n.path("relevant").forEach(r -> rel.add(r.asText()));
            // deterministic interleaved split so every question type lands in both halves
            int ordinal = rel.isEmpty() ? neg++ : pos++;
            String split = n.path("split").asText(ordinal % 2 == 0 ? "dev" : "test");
            out.add(new GoldenQuery(n.get("q").asText(), rel, n.path("type").asText("natural"), split));
        }
        return out;
    }

    static void write(Report report, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(outDir.resolve("results.json").toFile(), report);
        Files.writeString(outDir.resolve("results.md"), markdown(report));
    }

    static String markdown(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("## GitContext retrieval eval\n\n");
        sb.append(String.format(Locale.ROOT, "Corpus: %d files, %d chunks | embedder: %s | reranker: %s | %d questions + %d out-of-scope%n%n",
                r.files(), r.chunks(), r.embedder(), r.reranker(), r.positives(), r.negatives()));
        sb.append("| Mode | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR | P@5 | Citation acc. | p50 ms | p95 ms |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (ModeResult m : r.modes()) {
            sb.append(String.format(Locale.ROOT, "| %s | %.3f | %.3f | **%.3f** | %.3f | %.3f | %.3f | %.3f | %.2f | %.2f |%n",
                    m.mode(), m.recall1(), m.recall3(), m.recall5(), m.recall10(), m.mrr(), m.precision5(),
                    m.citationAccuracy(), m.p50Ms(), m.p95Ms()));
        }
        sb.append(String.format(Locale.ROOT, "%nHeld-out half only (%d questions never looked at while tuning):%n%n",
                r.heldOutModes().get(0).queries()));
        sb.append("| Mode | Recall@1 | Recall@5 | MRR | Citation acc. |\n|---|---|---|---|---|\n");
        for (ModeResult m : r.heldOutModes()) {
            sb.append(String.format(Locale.ROOT, "| %s | %.3f | **%.3f** | %.3f | %.3f |%n",
                    m.mode(), m.recall1(), m.recall5(), m.mrr(), m.citationAccuracy()));
        }
        sb.append("\nRecall@5 by question type:\n\n| Mode | ");
        List<String> types = new ArrayList<>(r.modes().get(0).recall5ByType().keySet());
        sb.append(String.join(" | ", types)).append(" |\n|---|").append("---|".repeat(types.size())).append("\n");
        for (ModeResult m : r.modes()) {
            sb.append("| ").append(m.mode()).append(" | ");
            for (String t : types) {
                sb.append(String.format(Locale.ROOT, "%.3f | ", m.recall5ByType().getOrDefault(t, 0.0)));
            }
            sb.append("\n");
        }
        sb.append(String.format(Locale.ROOT, "%nRetrieval-failure guard: abstained on %.0f%% of out-of-scope questions, "
                + "false abstain on %.0f%% of answerable ones.%n", r.abstainOnNegatives() * 100, r.falseAbstainOnPositives() * 100));
        if (!r.misses().isEmpty()) {
            sb.append("\nHYBRID_RERANK misses (not in top 5):\n\n");
            r.misses().forEach(m -> sb.append("- ").append(m).append("\n"));
        }
        return sb.toString();
    }
}
