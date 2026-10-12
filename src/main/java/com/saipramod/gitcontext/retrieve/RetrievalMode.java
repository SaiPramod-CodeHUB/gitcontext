package com.saipramod.gitcontext.retrieve;

public enum RetrievalMode {
    /** Keyword only. */
    BM25,
    /** Dense only. */
    VECTOR,
    /** BM25 + dense, fused with Reciprocal Rank Fusion. */
    HYBRID,
    /** HYBRID candidates, re-ordered by a second-stage reranker. */
    HYBRID_RERANK
}
