package com.saipramod.gitcontext.retrieve;

import com.saipramod.gitcontext.core.Chunk;
import com.saipramod.gitcontext.index.Bm25Index;
import com.saipramod.gitcontext.index.ScoredId;
import com.saipramod.gitcontext.index.VectorIndex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * BM25 and dense search run independently and are merged with Reciprocal Rank Fusion:
 * score(d) = sum over lists of 1 / (k + rank(d)). RRF needs no score calibration between
 * the two retrievers, which is why it is the default hybrid strategy in most vector DBs.
 */
public class HybridRetriever {

    public static final int RRF_K = 60;
    static final int PER_LIST_GUARANTEE = 10;

    private final Bm25Index bm25;
    private final VectorIndex vectors;
    private final Reranker reranker;
    private final Function<String, Chunk> lookup;
    private final int candidatesPerList;
    private final int rerankDepth;

    public HybridRetriever(Bm25Index bm25, VectorIndex vectors, Reranker reranker, Function<String, Chunk> lookup,
                           int candidatesPerList, int rerankDepth) {
        this.bm25 = bm25;
        this.vectors = vectors;
        this.reranker = reranker;
        this.lookup = lookup;
        this.candidatesPerList = candidatesPerList;
        this.rerankDepth = rerankDepth;
        reranker.attach(bm25);
    }

    public List<RetrievedChunk> retrieve(String query, int k, RetrievalMode mode) {
        return switch (mode) {
            case BM25 -> fromList(bm25.search(query, k), true);
            case VECTOR -> fromList(vectors.search(query, k), false);
            case HYBRID -> fuse(query).stream().limit(k).toList();
            case HYBRID_RERANK -> {
                // Candidate pool = fused head plus the top of each individual list. RRF alone can bury a
                // document that only one retriever found (e.g. an exact error string BM25 ranks #3 but
                // the dense index never sees), and a reranker cannot promote what it never receives.
                List<RetrievedChunk> fused = fuse(query);
                List<RetrievedChunk> pool = new ArrayList<>();
                for (int i = 0; i < fused.size(); i++) {
                    RetrievedChunk rc = fused.get(i);
                    boolean topOfAList = (rc.bm25Rank() > 0 && rc.bm25Rank() <= PER_LIST_GUARANTEE)
                            || (rc.vectorRank() > 0 && rc.vectorRank() <= PER_LIST_GUARANTEE);
                    if (i < rerankDepth || topOfAList) {
                        pool.add(rc);
                    }
                }
                yield reranker.rerank(query, pool).stream().limit(k).toList();
            }
        };
    }

    List<RetrievedChunk> fuse(String query) {
        List<ScoredId> kw = bm25.search(query, candidatesPerList);
        List<ScoredId> dense = vectors.search(query, candidatesPerList);
        Map<String, Integer> kwRank = ranks(kw);
        Map<String, Integer> denseRank = ranks(dense);
        Set<String> ids = new LinkedHashSet<>(kwRank.keySet());
        ids.addAll(denseRank.keySet());

        List<RetrievedChunk> fused = new ArrayList<>();
        for (String id : ids) {
            int r1 = kwRank.getOrDefault(id, 0);
            int r2 = denseRank.getOrDefault(id, 0);
            double score = (r1 > 0 ? 1.0 / (RRF_K + r1) : 0) + (r2 > 0 ? 1.0 / (RRF_K + r2) : 0);
            Chunk c = lookup.apply(id);
            if (c != null) {
                fused.add(new RetrievedChunk(c, score, r1, r2));
            }
        }
        fused.sort((a, b) -> Double.compare(b.score(), a.score()));
        return fused;
    }

    private List<RetrievedChunk> fromList(List<ScoredId> list, boolean keyword) {
        List<RetrievedChunk> out = new ArrayList<>();
        int rank = 1;
        for (ScoredId s : list) {
            Chunk c = lookup.apply(s.id());
            if (c != null) {
                out.add(new RetrievedChunk(c, s.score(), keyword ? rank : 0, keyword ? 0 : rank));
            }
            rank++;
        }
        return out;
    }

    private static Map<String, Integer> ranks(List<ScoredId> list) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            m.put(list.get(i).id(), i + 1);
        }
        return m;
    }

    public String rerankerName() {
        return reranker.name();
    }
}
