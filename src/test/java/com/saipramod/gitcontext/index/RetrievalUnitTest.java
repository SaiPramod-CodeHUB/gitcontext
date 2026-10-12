package com.saipramod.gitcontext.index;

import com.saipramod.gitcontext.core.Chunk;
import com.saipramod.gitcontext.core.ChunkKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalUnitTest {

    private static Chunk chunk(String id, String symbol, String text) {
        return new Chunk(id, id + ".java", ChunkKind.METHOD, symbol, 1, 5, text, Map.of());
    }

    @Test
    void tokenizerSplitsIdentifiersAndKeepsWholeName() {
        assertThat(Tokenizer.tokenize("fuseWithRrf(MAX_RETRY_COUNT)"))
                .contains("fusewithrrf", "fuse", "rrf", "max_retry_count", "max", "retry", "count");
        assertThat(Tokenizer.tokenize("parseHTTPResponse")).contains("parse", "http", "response");
    }

    @Test
    void bm25RanksExactIdentifierFirst() {
        Bm25Index idx = new Bm25Index();
        idx.build(List.of(
                chunk("a", "Cache.evictExpired", "removes expired cache entries on a timer"),
                chunk("b", "Payment.charge", "charges the card and retries on timeout"),
                chunk("c", "Payment.refund", "refunds a payment")));
        assertThat(idx.search("evictExpired", 3)).first().extracting(ScoredId::id).isEqualTo("a");
        assertThat(idx.search("retry on timeout", 3)).first().extracting(ScoredId::id).isEqualTo("b");
    }

    @Test
    void hashingEmbedderMatchesConceptSynonyms() {
        VectorIndex v = new VectorIndex(new HashingEmbedder());
        v.build(List.of(
                chunk("a", "Cache.evictEntries", "delete old entries from the cache"),
                chunk("b", "Payment.charge", "charges the card")));
        // "remove" never appears in the corpus; the concept lexicon links it to "delete"/"evict"
        assertThat(v.search("remove items from cache", 2)).first().extracting(ScoredId::id).isEqualTo("a");
    }

    @Test
    void embeddingsAreUnitLength() {
        float[] e = new HashingEmbedder().embed("hybrid retrieval with reciprocal rank fusion");
        double norm = 0;
        for (float x : e) {
            norm += x * x;
        }
        assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }
}
