package com.saipramod.gitcontext.answer;

import java.util.List;

public record Answer(
        String question,
        String answer,
        boolean abstained,
        boolean grounded,
        List<Integer> invalidCitations,
        List<Source> sources,
        String mode,
        String generator,
        long retrievalMs,
        long generationMs) {

    public record Source(int n, String location, String kind, String symbol, double score,
                         String commit, String author, String lastModified, boolean cited) { }
}
