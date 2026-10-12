package com.saipramod.gitcontext.answer;

import com.saipramod.gitcontext.retrieve.RetrievedChunk;

import java.util.List;

/** Produces an answer that cites context blocks as [1], [2]... */
public interface AnswerGenerator {

    String generate(String question, List<RetrievedChunk> context);

    String name();
}
