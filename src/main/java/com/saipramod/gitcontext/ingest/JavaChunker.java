package com.saipramod.gitcontext.ingest;

import com.saipramod.gitcontext.core.Chunk;
import com.saipramod.gitcontext.core.ChunkKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structure-aware Java chunker. It does not need a full parser: a small lexer that
 * skips strings, char literals, text blocks and comments is enough to match braces
 * reliably, and the text before each '{' tells us whether the block is a type or a
 * method. Output: one CLASS chunk per type (javadoc + signature + fields) and one
 * METHOD chunk per method/constructor (javadoc + annotations + body).
 */
public class JavaChunker {

    private static final Pattern TYPE_DECL =
            Pattern.compile("\\b(class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern METHOD_DECL =
            Pattern.compile("([A-Za-z_$][\\w$]*)\\s*\\([^()]*(?:\\([^()]*\\)[^()]*)*\\)\\s*(throws\\s+[\\w.$,\\s]+)?$",
                    Pattern.DOTALL);
    private static final Set<String> NOT_METHODS = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "try", "else", "do", "return", "new", "super", "this");

    private static final int MAX_CLASS_HEADER_LINES = 60;

    private enum BlockType { TYPE, METHOD, OTHER }

    private static final class Block {
        final BlockType type;
        final String name;
        final int headerStart;
        int firstMethodStart = -1;

        Block(BlockType type, String name, int headerStart) {
            this.type = type;
            this.name = name;
            this.headerStart = headerStart;
        }
    }

    public List<Chunk> chunk(String path, String source, Map<String, String> fileMeta) {
        List<Chunk> out = new ArrayList<>();
        int[] lineStarts = lineStarts(source);
        Deque<Block> stack = new ArrayDeque<>();
        int boundary = 0;   // index just after the last ';', '{' or '}' at statement level
        int parenDepth = 0;
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : '\0';
            // --- skip non-code regions ---
            if (c == '/' && next == '/') {
                i = skipTo(source, i, "\n");
                continue;
            }
            if (c == '/' && next == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (c == '"' && source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                i = end < 0 ? n : end + 3;
                continue;
            }
            if (c == '"' || c == '\'') {
                i = skipLiteral(source, i, c);
                continue;
            }
            // --- structure ---
            switch (c) {
                case '(' -> parenDepth++;
                case ')' -> parenDepth = Math.max(0, parenDepth - 1);
                case ';' -> {
                    if (parenDepth == 0) {
                        boundary = i + 1;
                    }
                }
                case '{' -> {
                    int headerStart = skipWhitespace(source, boundary, i);
                    String header = source.substring(headerStart, i).strip();
                    Block parent = stack.peek();
                    Block block = classify(header, parent, headerStart, parenDepth);
                    if (block.type == BlockType.METHOD && parent != null && parent.firstMethodStart < 0) {
                        parent.firstMethodStart = headerStart;
                    }
                    if (block.type == BlockType.TYPE && parent != null && parent.type == BlockType.TYPE
                            && parent.firstMethodStart < 0) {
                        parent.firstMethodStart = headerStart; // nested type ends the class "header" region too
                    }
                    stack.push(block);
                    boundary = i + 1;
                }
                case '}' -> {
                    if (!stack.isEmpty()) {
                        Block closed = stack.pop();
                        emit(out, closed, stack, path, source, lineStarts, i, fileMeta);
                    }
                    boundary = i + 1;
                }
                default -> { }
            }
            i++;
        }
        return out;
    }

    private Block classify(String header, Block parent, int headerStart, int parenDepth) {
        if (parenDepth > 0) {
            return new Block(BlockType.OTHER, "", headerStart);
        }
        boolean parentIsTypeOrTop = parent == null || parent.type == BlockType.TYPE;
        String code = stripAnnotationsAndComments(header);
        Matcher type = TYPE_DECL.matcher(code);
        if (parentIsTypeOrTop && type.find() && !code.contains("=") && !code.startsWith("new ")) {
            String name = parent == null ? type.group(2) : parent.name + "." + type.group(2);
            return new Block(BlockType.TYPE, name, headerStart);
        }
        if (parent != null && parent.type == BlockType.TYPE) {
            Matcher m = METHOD_DECL.matcher(code);
            if (m.find() && !NOT_METHODS.contains(m.group(1)) && !code.contains("=") && !code.contains("->")) {
                return new Block(BlockType.METHOD, parent.name + "." + m.group(1), headerStart);
            }
        }
        return new Block(BlockType.OTHER, "", headerStart);
    }

    private void emit(List<Chunk> out, Block closed, Deque<Block> stack, String path, String source,
                      int[] lineStarts, int closeIdx, Map<String, String> fileMeta) {
        if (closed.type == BlockType.METHOD) {
            out.add(build(path, ChunkKind.METHOD, closed.name, source, lineStarts,
                    closed.headerStart, closeIdx + 1, fileMeta));
        } else if (closed.type == BlockType.TYPE) {
            int end = closed.firstMethodStart > 0 ? closed.firstMethodStart : closeIdx + 1;
            int startLine = lineOf(lineStarts, closed.headerStart);
            int endLine = lineOf(lineStarts, Math.max(closed.headerStart, end - 1));
            if (endLine - startLine + 1 > MAX_CLASS_HEADER_LINES) {
                end = lineStarts[Math.min(lineStarts.length - 1, startLine - 1 + MAX_CLASS_HEADER_LINES)];
            }
            out.add(build(path, ChunkKind.CLASS, closed.name, source, lineStarts, closed.headerStart, end, fileMeta));
        }
    }

    private Chunk build(String path, ChunkKind kind, String symbol, String source, int[] lineStarts,
                        int start, int endExclusive, Map<String, String> fileMeta) {
        String text = source.substring(start, Math.min(endExclusive, source.length())).stripTrailing();
        int startLine = lineOf(lineStarts, start);
        int endLine = lineOf(lineStarts, Math.max(start, endExclusive - 1));
        Map<String, String> meta = new HashMap<>(fileMeta);
        meta.put("language", "java");
        return new Chunk(path + "#" + symbol + "@" + startLine, path, kind, symbol, startLine, endLine, text, meta);
    }

    // ---------------------------------------------------------------- helpers

    static String stripAnnotationsAndComments(String header) {
        String noBlock = header.replaceAll("(?s)/\\*.*?\\*/", " ");
        String noLine = noBlock.replaceAll("//[^\n]*", " ");
        // annotations, with or without arguments (one level of parentheses)
        String noAnn = noLine.replaceAll("@[\\w.]+(\\s*\\([^()]*(\\([^()]*\\)[^()]*)*\\))?", " ");
        return noAnn.replaceAll("\\s+", " ").strip();
    }

    private static int skipTo(String s, int from, String token) {
        int idx = s.indexOf(token, from);
        return idx < 0 ? s.length() : idx;
    }

    private static int skipLiteral(String s, int from, char quote) {
        int i = from + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote || c == '\n') {
                return i + 1;
            }
            i++;
        }
        return s.length();
    }

    private static int skipWhitespace(String s, int from, int limit) {
        int i = from;
        while (i < limit && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    static int[] lineStarts(String s) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 1-based line number of a character index. */
    static int lineOf(int[] lineStarts, int idx) {
        int lo = 0;
        int hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= idx) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo + 1;
    }
}
