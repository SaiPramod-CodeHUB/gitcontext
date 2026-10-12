package com.saipramod.gitcontext.index;

import com.saipramod.gitcontext.core.Chunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Exact (brute-force) cosine search. At repository scale (tens of thousands of chunks)
 * this is a few milliseconds; the interface is deliberately small so it can be backed by
 * Qdrant / pgvector / Pinecone without touching the retriever.
 */
public class VectorIndex {

    private final Embedder embedder;
    private final Map<String, float[]> vectors = new LinkedHashMap<>();

    public VectorIndex(Embedder embedder) {
        this.embedder = embedder;
    }

    public void build(List<Chunk> chunks) {
        vectors.clear();
        chunks.forEach(this::upsert);
    }

    public void upsert(Chunk chunk) {
        vectors.put(chunk.id(), embedder.embed(chunk.indexText()));
    }

    public void remove(String id) {
        vectors.remove(id);
    }

    public List<ScoredId> search(String query, int k) {
        float[] q = embedder.embed(query);
        PriorityQueue<ScoredId> top = new PriorityQueue<>(Comparator.comparingDouble(ScoredId::score));
        vectors.forEach((id, v) -> {
            double s = dot(q, v);
            if (s > 0) {
                top.offer(new ScoredId(id, s));
                if (top.size() > k) {
                    top.poll();
                }
            }
        });
        List<ScoredId> out = new ArrayList<>(top);
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out;
    }

    private static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    public String embedderName() {
        return embedder.name();
    }

    public int size() {
        return vectors.size();
    }
}
