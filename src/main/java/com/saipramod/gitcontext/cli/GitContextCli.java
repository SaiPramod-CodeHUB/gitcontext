package com.saipramod.gitcontext.cli;

import com.saipramod.gitcontext.answer.Answer;
import com.saipramod.gitcontext.answer.ExtractiveAnswerGenerator;
import com.saipramod.gitcontext.index.HashingEmbedder;
import com.saipramod.gitcontext.retrieve.HeuristicReranker;
import com.saipramod.gitcontext.retrieve.RetrievalMode;
import com.saipramod.gitcontext.service.AskService;
import com.saipramod.gitcontext.service.CodebaseIndex;

import java.nio.file.Path;
import java.util.Scanner;

/**
 * Terminal client, fully offline.
 *
 * <pre>mvn -q compile exec:java -Dexec.mainClass=com.saipramod.gitcontext.cli.GitContextCli -Dexec.args="/path/to/repo"</pre>
 * Type a question per line; ":mode BM25|VECTOR|HYBRID|HYBRID_RERANK" switches retrieval, ":q" quits.
 * For LLM answers use the HTTP API with LLM_PROVIDER=anthropic.
 */
public final class GitContextCli {

    private GitContextCli() { }

    public static void main(String[] args) {
        Path repo = Path.of(args.length > 0 ? args[0] : ".");
        CodebaseIndex index = new CodebaseIndex(new HashingEmbedder(), new HeuristicReranker(), true);
        var stats = index.index(repo);
        System.out.printf("Indexed %d files -> %d chunks %s%n", stats.files(), stats.chunks(), stats.chunksByKind());
        AskService ask = new AskService(index, new ExtractiveAnswerGenerator(),
                AskService.DEFAULT_MIN_EVIDENCE);
        RetrievalMode mode = RetrievalMode.HYBRID_RERANK;
        Scanner in = new Scanner(System.in);
        System.out.print("\ngitcontext> ");
        while (in.hasNextLine()) {
            String line = in.nextLine().strip();
            if (line.equals(":q")) {
                break;
            }
            if (line.startsWith(":mode ")) {
                mode = RetrievalMode.valueOf(line.substring(6).strip().toUpperCase());
                System.out.println("mode = " + mode);
            } else if (!line.isEmpty()) {
                Answer a = ask.ask(line, 5, mode);
                System.out.println("\n" + a.answer() + "\n");
                a.sources().forEach(s -> System.out.printf("  [%d] %-60s %-11s %s%n", s.n(), s.location(), s.kind(), s.symbol()));
                System.out.printf("  (%d ms retrieval, %s)%n", a.retrievalMs(), a.mode());
            }
            System.out.print("\ngitcontext> ");
        }
    }
}
