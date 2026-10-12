package com.saipramod.gitcontext.retrieve;

import com.saipramod.gitcontext.core.Chunk;

/**
 * @param bm25Rank   1-based rank in the keyword list, 0 if absent
 * @param vectorRank 1-based rank in the dense list, 0 if absent
 */
public record RetrievedChunk(Chunk chunk, double score, int bm25Rank, int vectorRank) {

    public RetrievedChunk withScore(double newScore) {
        return new RetrievedChunk(chunk, newScore, bm25Rank, vectorRank);
    }
}
