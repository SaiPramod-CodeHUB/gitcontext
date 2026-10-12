package com.saipramod.gitcontext.answer;

import com.saipramod.gitcontext.core.Chunk;
import com.saipramod.gitcontext.core.ChunkKind;
import com.saipramod.gitcontext.retrieve.RetrievedChunk;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * No-LLM answerer used by default. It never invents content: it points at the best
 * matching code, quotes its doc comment (or first meaningful lines) and lists related
 * locations, all with citations. Good enough to demo and to evaluate citation logic.
 */
public class ExtractiveAnswerGenerator implements AnswerGenerator {

    static final double RELATED_MIN_RATIO = 0.85;
    private static final Pattern JAVADOC = Pattern.compile("/\\*\\*(.*?)\\*/", Pattern.DOTALL);

    @Override
    public String generate(String question, List<RetrievedChunk> context) {
        if (context.isEmpty()) {
            return "I couldn't find anything relevant in the indexed repository.";
        }
        Chunk top = context.get(0).chunk();
        StringBuilder sb = new StringBuilder();
        sb.append("Most relevant: ").append(describe(top)).append(" [1].");
        String summary = summary(top);
        if (!summary.isBlank()) {
            sb.append("\n\n> ").append(summary.replace("\n", "\n> "));
        }
        // Only cite neighbours that scored close to the top hit; citing weak matches dilutes precision.
        double topScore = context.get(0).score();
        StringBuilder related = new StringBuilder();
        for (int i = 1; i < Math.min(context.size(), 4); i++) {
            if (context.get(i).score() >= RELATED_MIN_RATIO * topScore) {
                related.append("\n- ").append(describe(context.get(i).chunk())).append(" [").append(i + 1).append("]");
            }
        }
        if (!related.isEmpty()) {
            sb.append("\n\nRelated:").append(related);
        }
        return sb.toString();
    }

    private static String describe(Chunk c) {
        String what = switch (c.kind()) {
            case METHOD -> "method `" + c.symbol() + "`";
            case CLASS -> "type `" + c.symbol() + "`";
            case COMMIT -> "commit \"" + c.symbol() + "\"";
            case DOC_SECTION, INCIDENT -> "section \"" + c.symbol() + "\"";
            default -> "`" + c.path() + "`";
        };
        return c.kind() == ChunkKind.COMMIT ? what + " (" + c.path() + ")" : what + " in `" + c.location() + "`";
    }

    static String summary(Chunk c) {
        Matcher m = JAVADOC.matcher(c.text());
        if (m.find()) {
            String doc = m.group(1).replaceAll("(?m)^\\s*\\*\\s?", "").strip();
            int para = doc.indexOf("\n\n");
            doc = para > 0 ? doc.substring(0, para) : doc;
            return doc.replaceAll("<[^>]+>", "").replaceAll("\\{@\\w+\\s+([^}]*)}", "$1").strip();
        }
        return c.text().lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("import") && !l.startsWith("package") && !l.startsWith("@"))
                .limit(4)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    @Override
    public String name() {
        return "extractive (no LLM)";
    }
}
