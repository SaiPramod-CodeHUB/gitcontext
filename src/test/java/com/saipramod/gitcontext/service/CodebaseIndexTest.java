package com.saipramod.gitcontext.service;

import com.saipramod.gitcontext.answer.Answer;
import com.saipramod.gitcontext.answer.ExtractiveAnswerGenerator;
import com.saipramod.gitcontext.index.HashingEmbedder;
import com.saipramod.gitcontext.retrieve.HeuristicReranker;
import com.saipramod.gitcontext.retrieve.RetrievalMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CodebaseIndexTest {

    @TempDir
    Path repo;
    CodebaseIndex index;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/RateLimiter.java"), """
                /** Token bucket limiter. */
                public class RateLimiter {
                    /** Refills tokens based on elapsed time. */
                    public void refill(long nowMillis) { tokens = Math.min(capacity, tokens + rate * nowMillis); }
                }
                """);
        Files.writeString(repo.resolve("src/OrderService.java"), """
                public class OrderService {
                    public void cancelOrder(String id) {
                        throw new IllegalStateException("Order already shipped");
                    }
                }
                """);
        Files.writeString(repo.resolve("README.md"), "# Shop\n\n## Running\n\nUse mvn spring-boot:run.\n");
        index = new CodebaseIndex(new HashingEmbedder(), new HeuristicReranker(), false);
        index.index(repo);
    }

    @Test
    void findsMethodByErrorMessage() {
        var hits = index.search("\"Order already shipped\"", 3, RetrievalMode.HYBRID_RERANK);
        assertThat(hits.get(0).chunk().symbol()).isEqualTo("OrderService.cancelOrder");
    }

    @Test
    void reindexOnlyTouchesChangedAddedAndDeletedFiles() throws IOException {
        Files.writeString(repo.resolve("src/OrderService.java"), """
                public class OrderService {
                    public void cancelOrder(String id) { audit(id); }
                    void audit(String id) { }
                }
                """);
        Files.writeString(repo.resolve("src/Inventory.java"), "public class Inventory { void reserve() { } }");
        Files.delete(repo.resolve("README.md"));

        CodebaseIndex.ReindexReport report = index.reindexChanged();

        assertThat(report.changed()).containsExactly("src/OrderService.java");
        assertThat(report.added()).containsExactly("src/Inventory.java");
        assertThat(report.deleted()).containsExactly("README.md");
        assertThat(index.search("audit", 3, RetrievalMode.BM25).get(0).chunk().symbol()).isEqualTo("OrderService.audit");
        assertThat(index.reindexChanged().noop()).isTrue();
    }

    @Test
    void abstainsWhenNothingRelevantIsIndexed() {
        AskService ask = new AskService(index, new ExtractiveAnswerGenerator(),
                AskService.DEFAULT_MIN_EVIDENCE);
        Answer a = ask.ask("kubernetes horizontal pod autoscaler", 5, RetrievalMode.HYBRID_RERANK);
        assertThat(a.abstained()).isTrue();
        assertThat(a.sources()).isEmpty();
    }

    @Test
    void extractiveAnswerIsGroundedWithVerifiedCitations() {
        AskService ask = new AskService(index, new ExtractiveAnswerGenerator(),
                AskService.DEFAULT_MIN_EVIDENCE);
        Answer a = ask.ask("how are rate limiter tokens refilled", 3, RetrievalMode.HYBRID_RERANK);
        assertThat(a.grounded()).isTrue();
        assertThat(a.sources().get(0).location()).startsWith("src/RateLimiter.java");
    }
}
