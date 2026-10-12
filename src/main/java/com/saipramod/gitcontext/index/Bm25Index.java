package com.saipramod.gitcontext.index;

import com.saipramod.gitcontext.core.Chunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Okapi BM25 over chunk text. Keyword search is what finds exact error messages,
 * config keys and function names, which dense embeddings tend to blur.
 */
public class Bm25Index {

    private final double k1;
    private final double b;
    private final List<Chunk> docs = new ArrayList<>();
    private final List<Map<String, Integer>> termFreqs = new ArrayList<>();
    private final List<Integer> docLengths = new ArrayList<>();
    private final Map<String, Integer> docFreq = new HashMap<>();
    private double avgDocLength;

    public Bm25Index() {
        this(1.2, 0.75);
    }

    public Bm25Index(double k1, double b) {
        this.k1 = k1;
        this.b = b;
    }

    public void build(List<Chunk> chunks) {
        docs.clear();
        termFreqs.clear();
        docLengths.clear();
        docFreq.clear();
        long total = 0;
        for (Chunk c : chunks) {
            List<String> tokens = Tokenizer.tokenize(c.indexText());
            Map<String, Integer> tf = new HashMap<>();
            tokens.forEach(t -> tf.merge(t, 1, Integer::sum));
            tf.keySet().forEach(t -> docFreq.merge(t, 1, Integer::sum));
            docs.add(c);
            termFreqs.add(tf);
            docLengths.add(tokens.size());
            total += tokens.size();
        }
        avgDocLength = docs.isEmpty() ? 0 : (double) total / docs.size();
    }

    public List<ScoredId> search(String query, int k) {
        List<String> qTerms = Tokenizer.tokenize(query).stream().distinct().toList();
        PriorityQueue<ScoredId> top = new PriorityQueue<>(java.util.Comparator.comparingDouble(ScoredId::score));
        int n = docs.size();
        for (int d = 0; d < n; d++) {
            double score = 0;
            Map<String, Integer> tf = termFreqs.get(d);
            double norm = k1 * (1 - b + b * docLengths.get(d) / Math.max(1e-9, avgDocLength));
            for (String term : qTerms) {
                Integer f = tf.get(term);
                if (f == null) {
                    continue;
                }
                score += idf(term) * (f * (k1 + 1)) / (f + norm);
            }
            if (score > 0) {
                top.offer(new ScoredId(docs.get(d).id(), score));
                if (top.size() > k) {
                    top.poll();
                }
            }
        }
        List<ScoredId> out = new ArrayList<>(top);
        out.sort((x, y) -> Double.compare(y.score(), x.score()));
        return out;
    }

    /**
     * Share of the query's information (idf mass) that the best of the top-k keyword hits
     * contains. Low coverage means only common words matched, i.e. the question is probably
     * about something that is not in this repository.
     */
    public double bestCoverage(String query, int k) {
        List<String> qTerms = Tokenizer.tokenize(query).stream().distinct().toList();
        if (qTerms.isEmpty()) {
            return 0;
        }
        double total = qTerms.stream().mapToDouble(this::idf).sum();
        Map<String, Integer> idToDoc = new HashMap<>();
        for (int d = 0; d < docs.size(); d++) {
            idToDoc.put(docs.get(d).id(), d);
        }
        double best = 0;
        for (ScoredId hit : search(query, k)) {
            Map<String, Integer> tf = termFreqs.get(idToDoc.get(hit.id()));
            double covered = qTerms.stream().filter(tf::containsKey).mapToDouble(this::idf).sum();
            best = Math.max(best, covered / total);
        }
        return best;
    }

    /** BM25+ style idf, always positive. Unknown terms get the maximum idf. */
    public double idf(String term) {
        int df = docFreq.getOrDefault(term, 0);
        return Math.log(1 + (docs.size() - df + 0.5) / (df + 0.5));
    }

    public int size() {
        return docs.size();
    }
}
