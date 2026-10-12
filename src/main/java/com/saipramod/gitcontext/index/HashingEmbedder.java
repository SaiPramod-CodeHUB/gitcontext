package com.saipramod.gitcontext.index;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline embedder so the project runs and evaluates with no API key or model download.
 *
 * <p>It is a feature-hashing model, not a neural one: identifier sub-tokens, character
 * 4-grams (fuzzy matching of "retries" vs "retry", typos, partial names) and a small
 * concept lexicon (remove ~ delete, exception ~ error...) are hashed into a fixed-size
 * vector with sublinear term weighting. That gives it a different error profile from
 * BM25, which is what hybrid fusion needs. Swap in a real model with
 * {@link SpringAiEmbedder} (OpenAI, Ollama, ONNX all-MiniLM) for true semantic recall.
 */
public class HashingEmbedder implements Embedder {

    private static final Map<String, String> CONCEPTS = Map.ofEntries(
            Map.entry("delete", "c_remove"), Map.entry("remove", "c_remove"), Map.entry("evict", "c_remove"),
            Map.entry("drop", "c_remove"), Map.entry("purge", "c_remove"),
            Map.entry("error", "c_error"), Map.entry("exception", "c_error"), Map.entry("fail", "c_error"),
            Map.entry("failure", "c_error"), Map.entry("throw", "c_error"), Map.entry("invalid", "c_error"),
            Map.entry("config", "c_config"), Map.entry("configuration", "c_config"), Map.entry("setting", "c_config"),
            Map.entry("property", "c_config"), Map.entry("yml", "c_config"), Map.entry("env", "c_config"),
            Map.entry("search", "c_search"), Map.entry("retrieve", "c_search"), Map.entry("retrieval", "c_search"),
            Map.entry("lookup", "c_search"), Map.entry("find", "c_search"), Map.entry("query", "c_search"),
            Map.entry("score", "c_rank"), Map.entry("rank", "c_rank"), Map.entry("rerank", "c_rank"),
            Map.entry("relevance", "c_rank"), Map.entry("sort", "c_rank"),
            Map.entry("stale", "c_fresh"), Map.entry("outdat", "c_fresh"), Map.entry("chang", "c_fresh"),
            Map.entry("modifi", "c_fresh"), Map.entry("reindex", "c_fresh"), Map.entry("refresh", "c_fresh"),
            Map.entry("split", "c_chunk"), Map.entry("chunk", "c_chunk"), Map.entry("segment", "c_chunk"),
            Map.entry("parse", "c_chunk"), Map.entry("vector", "c_embed"), Map.entry("embed", "c_embed"),
            Map.entry("embedding", "c_embed"), Map.entry("dense", "c_embed"), Map.entry("semantic", "c_embed"),
            Map.entry("cite", "c_cite"), Map.entry("citation", "c_cite"), Map.entry("source", "c_cite"),
            Map.entry("reference", "c_cite"), Map.entry("answer", "c_answer"), Map.entry("respond", "c_answer"),
            Map.entry("reply", "c_answer"), Map.entry("generat", "c_answer"), Map.entry("llm", "c_answer"),
            Map.entry("commit", "c_git"), Map.entry("git", "c_git"), Map.entry("history", "c_git"),
            Map.entry("author", "c_git"), Map.entry("latency", "c_perf"), Map.entry("slow", "c_perf"),
            Map.entry("timing", "c_perf"), Map.entry("percentile", "c_perf"), Map.entry("p95", "c_perf"),
            Map.entry("combine", "c_fuse"), Map.entry("merge", "c_fuse"), Map.entry("fuse", "c_fuse"),
            Map.entry("fusion", "c_fuse"), Map.entry("hybrid", "c_fuse"), Map.entry("rrf", "c_fuse"),
            Map.entry("word", "c_token"), Map.entry("token", "c_token"), Map.entry("camel", "c_token"),
            Map.entry("identifier", "c_token"));

    private final int dims;

    public HashingEmbedder() {
        this(1024);
    }

    public HashingEmbedder(int dims) {
        this.dims = dims;
    }

    @Override
    public float[] embed(String text) {
        List<String> tokens = Tokenizer.tokenize(text);
        Map<String, Double> features = new HashMap<>();
        for (String t : tokens) {
            features.merge("t:" + t, 1.0, Double::sum);
            String concept = concept(t);
            if (concept != null) {
                features.merge(concept, 1.0, Double::sum);
            }
            String padded = "^" + t + "$";
            for (int i = 0; i + 4 <= padded.length(); i++) {
                features.merge("g:" + padded.substring(i, i + 4), 0.35, Double::sum);
            }
        }
        float[] v = new float[dims];
        features.forEach((f, w) -> {
            int h = murmurish(f);
            int idx = Math.floorMod(h, dims);
            float sign = ((h >>> 31) == 0) ? 1f : -1f;
            double weight = w >= 1 ? 1 + Math.log(w) : w;   // sublinear tf
            v[idx] += (float) (sign * weight);
        });
        return Embedder.normalize(v);
    }

    private static String concept(String token) {
        String direct = CONCEPTS.get(token);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> e : CONCEPTS.entrySet()) {
            if (e.getKey().length() >= 5 && token.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    private static int murmurish(String s) {
        int h = 0x9747b28c;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x5bd1e995;
            h ^= h >>> 15;
        }
        return h;
    }

    @Override
    public String name() {
        return "hashing-" + dims;
    }
}
