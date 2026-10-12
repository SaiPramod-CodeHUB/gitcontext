package com.saipramod.gitcontext.ingest;

import com.saipramod.gitcontext.core.Chunk;
import com.saipramod.gitcontext.core.ChunkKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Splits Markdown along headings (h1-h3) so each chunk is one topic. Files that look like
 * incident reports / postmortems are tagged INCIDENT so they can be filtered or boosted.
 */
public class MarkdownChunker {

    private static final int MAX_SECTION_LINES = 80;

    public List<Chunk> chunk(String path, String source, Map<String, String> fileMeta) {
        String[] lines = source.split("\n", -1);
        ChunkKind kind = isIncident(path) ? ChunkKind.INCIDENT : ChunkKind.DOC_SECTION;
        List<Chunk> out = new ArrayList<>();
        int sectionStart = 0;
        String heading = fileTitle(path);
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.strip().startsWith("```")) {
                inFence = !inFence;
            }
            boolean isHeading = !inFence && line.matches("^#{1,3}\\s+.*");
            boolean tooLong = i - sectionStart >= MAX_SECTION_LINES;
            if ((isHeading || tooLong) && i > sectionStart) {
                add(out, path, kind, heading, lines, sectionStart, i, fileMeta);
                sectionStart = i;
            }
            if (isHeading) {
                heading = line.replaceFirst("^#+\\s+", "").strip();
            }
        }
        add(out, path, kind, heading, lines, sectionStart, lines.length, fileMeta);
        return out;
    }

    private void add(List<Chunk> out, String path, ChunkKind kind, String heading, String[] lines,
                     int from, int toExclusive, Map<String, String> fileMeta) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < toExclusive; i++) {
            sb.append(lines[i]).append('\n');
        }
        String text = sb.toString().strip();
        if (text.isEmpty()) {
            return;
        }
        Map<String, String> meta = new HashMap<>(fileMeta);
        meta.put("language", "markdown");
        int startLine = from + 1;
        out.add(new Chunk(path + "#" + heading + "@" + startLine, path, kind, heading, startLine, toExclusive, text, meta));
    }

    static boolean isIncident(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.contains("incident") || p.contains("postmortem") || p.contains("post-mortem") || p.contains("rca");
    }

    private static String fileTitle(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
