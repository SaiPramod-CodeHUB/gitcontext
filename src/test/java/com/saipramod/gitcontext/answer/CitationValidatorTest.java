package com.saipramod.gitcontext.answer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CitationValidatorTest {

    @Test
    void acceptsCitationsWithinContext() {
        var r = CitationValidator.validate("BM25 is built in Bm25Index [1], fused in [3].", 5);
        assertThat(r.cited()).containsExactly(1, 3);
        assertThat(r.grounded()).isTrue();
    }

    @Test
    void flagsCitationsToBlocksThatWereNeverProvided() {
        var r = CitationValidator.validate("See [2] and [7].", 3);
        assertThat(r.invalid()).containsExactly(7);
        assertThat(r.grounded()).isFalse();
    }

    @Test
    void answerWithoutCitationsIsNotGrounded() {
        assertThat(CitationValidator.validate("It is somewhere in the code.", 3).grounded()).isFalse();
    }
}
