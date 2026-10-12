package com.saipramod.gitcontext.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Code-aware tokenizer. "fuseWithRrf" yields [fusewithrrf, fuse, with, rrf] and
 * "MAX_RETRY_COUNT" yields [max_retry_count, max, retry, count], so both an exact
 * identifier and a natural-language description of it can match.
 */
public final class Tokenizer {

    private static final Set<String> STOP = Set.of(
            "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "is", "are", "be", "it", "this", "that",
            "with", "as", "by", "at", "from", "how", "what", "where", "which", "who", "why", "does", "do", "did",
            "can", "i", "we", "you", "me", "my", "our", "if", "else", "when", "then", "there", "its", "into",
            "public", "private", "protected", "static", "final", "void", "return", "new", "import", "package",
            "class", "string", "int", "boolean", "var", "null", "true", "false", "get", "set", "java", "util",
            "list", "map", "com", "saipramod");

    private Tokenizer() { }

    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.split("[^A-Za-z0-9_]+")) {
            if (raw.isEmpty()) {
                continue;
            }
            String whole = raw.toLowerCase(Locale.ROOT);
            List<String> parts = splitIdentifier(raw);
            if (parts.size() > 1) {
                addToken(out, whole);
            }
            for (String p : parts) {
                addToken(out, p);
            }
        }
        return out;
    }

    private static void addToken(List<String> out, String token) {
        String t = stem(token);
        if (t.length() < 2 || STOP.contains(t) || t.chars().allMatch(Character::isDigit)) {
            return;
        }
        out.add(t);
    }

    static List<String> splitIdentifier(String raw) {
        List<String> parts = new ArrayList<>();
        for (String piece : raw.split("_+")) {
            if (piece.isEmpty()) {
                continue;
            }
            // camelCase / PascalCase / acronyms: "parseHTTPResponse" -> parse, http, response
            for (String p : piece.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
                if (!p.isEmpty()) {
                    parts.add(p.toLowerCase(Locale.ROOT));
                }
            }
        }
        return parts;
    }

    /** Deliberately tiny stemmer: plural and -ing/-ed suffixes are the common mismatches in code search. */
    static String stem(String t) {
        if (t.length() > 5 && t.endsWith("ing")) {
            return t.substring(0, t.length() - 3);
        }
        if (t.length() > 4 && t.endsWith("ies")) {
            return t.substring(0, t.length() - 3) + "y";
        }
        if (t.length() > 4 && t.endsWith("ed") && !t.endsWith("eed")) {
            return t.substring(0, t.length() - 2);
        }
        if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss")) {
            return t.substring(0, t.length() - 1);
        }
        return t;
    }
}
