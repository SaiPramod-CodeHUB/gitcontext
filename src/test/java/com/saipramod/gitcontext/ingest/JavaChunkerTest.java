package com.saipramod.gitcontext.ingest;

import com.saipramod.gitcontext.core.Chunk;
import com.saipramod.gitcontext.core.ChunkKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JavaChunkerTest {

    private static final String SRC = """
            package demo;

            import java.util.List;

            /** Handles payments. */
            public class PaymentService {
                private static final String BRACE = "{ not a block }";
                private final int retries = 3;

                /** Charges a card, retrying on timeouts. */
                @Transactional
                public Receipt charge(String cardId, long cents) throws PaymentException {
                    if (cents <= 0) {
                        throw new IllegalArgumentException("amount must be positive");
                    }
                    Runnable r = () -> { System.out.println("}"); };
                    return new Receipt(cardId);
                }

                // comment with a { brace
                void refund(String id) {
                    char c = '{';
                }

                record Receipt(String id) { }

                enum Status { OK, FAILED }
            }
            """;

    @Test
    void splitsTypesAndMethodsWithLineRanges() {
        List<Chunk> chunks = new JavaChunker().chunk("src/PaymentService.java", SRC, Map.of());

        assertThat(chunks).extracting(Chunk::symbol).contains(
                "PaymentService", "PaymentService.charge", "PaymentService.refund",
                "PaymentService.Receipt", "PaymentService.Status");

        Chunk charge = chunks.stream().filter(c -> c.symbol().equals("PaymentService.charge")).findFirst().orElseThrow();
        assertThat(charge.kind()).isEqualTo(ChunkKind.METHOD);
        assertThat(charge.text()).startsWith("/** Charges a card").contains("@Transactional").endsWith("}");
        assertThat(charge.startLine()).isEqualTo(10);
        assertThat(charge.endLine()).isEqualTo(18);
    }

    @Test
    void classChunkHoldsHeaderAndFieldsButNotMethodBodies() {
        Chunk cls = new JavaChunker().chunk("P.java", SRC, Map.of()).stream()
                .filter(c -> c.kind() == ChunkKind.CLASS && c.symbol().equals("PaymentService"))
                .findFirst().orElseThrow();
        assertThat(cls.text()).contains("Handles payments").contains("retries = 3").doesNotContain("amount must be positive");
    }

    @Test
    void controlFlowBlocksAreNotMethods() {
        assertThat(new JavaChunker().chunk("P.java", SRC, Map.of()))
                .extracting(Chunk::symbol)
                .noneMatch(s -> s.endsWith(".if") || s.endsWith(".throw"));
    }
}
