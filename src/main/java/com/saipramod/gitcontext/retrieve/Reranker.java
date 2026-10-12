package com.saipramod.gitcontext.retrieve;

import java.util.List;

/** Second-stage scorer: sees the query and each candidate together (cross-encoder style). */
public interface Reranker {

    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates);

    String name();

    /** Gives the reranker corpus statistics (term rarity). Optional. */
    default void attach(com.saipramod.gitcontext.index.Bm25Index index) { }
}
