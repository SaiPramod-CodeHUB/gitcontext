package com.saipramod.gitcontext.core;

import java.util.Map;

/**
 * A retrievable unit of the codebase. Code is chunked along structural boundaries
 * (class, method), docs along headings, git history per commit, so a citation
 * always points to something a developer can open.
 *
 * @param id        stable id: path#symbol@startLine (or commit sha)
 * @param path      repo-relative file path (or "git:commit/<sha>")
 * @param kind      structural kind
 * @param symbol    class / method / heading name, may be empty
 * @param startLine 1-based inclusive
 * @param endLine   1-based inclusive
 * @param text      raw text of the chunk
 * @param metadata  commit, author, lastModified, contentHash, language...
 */
public record Chunk(
        String id,
        String path,
        ChunkKind kind,
        String symbol,
        int startLine,
        int endLine,
        String text,
        Map<String, String> metadata) {

    public String location() {
        if (kind == ChunkKind.COMMIT) {
            return path;
        }
        return path + ":L" + startLine + "-" + endLine;
    }

    /** Text used for indexing: symbol and path are strong signals, so they are included. */
    public String indexText() {
        return path + "\n" + (symbol == null ? "" : symbol) + "\n" + text;
    }
}
