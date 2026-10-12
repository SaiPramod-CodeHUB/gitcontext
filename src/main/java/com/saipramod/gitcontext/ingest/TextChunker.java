package com.saipramod.gitcontext.ingest;

import com.saipramod.gitcontext.core.Chunk;
import com.saipramod.gitcontext.core.ChunkKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fallback: overlapping line windows for config files and languages without a structural chunker. */
public class TextChunker {

    private static final Set<String> CONFIG_EXT = Set.of("yml", "yaml", "properties", "xml", "json", "toml", "conf");
    private final int window;
    private final int overlap;

    public TextChunker() {
        this(40, 10);
    }

    public TextChunker(int window, int overlap) {
        this.window = window;
        this.overlap = overlap;
    }

    public List<Chunk> chunk(String path, String source, Map<String, String> fileMeta) {
        String[] lines = source.split("\n", -1);
        ChunkKind kind = CONFIG_EXT.contains(extension(path)) ? ChunkKind.CONFIG : ChunkKind.TEXT;
        List<Chunk> out = new ArrayList<>();
        int step = Math.max(1, window - overlap);
        for (int start = 0; start < lines.length; start += step) {
            int end = Math.min(lines.length, start + window);
            String text = String.join("\n", java.util.Arrays.copyOfRange(lines, start, end)).strip();
            if (!text.isEmpty()) {
                Map<String, String> meta = new HashMap<>(fileMeta);
                meta.put("language", extension(path));
                out.add(new Chunk(path + "@" + (start + 1), path, kind, "", start + 1, end, text, meta));
            }
            if (end == lines.length) {
                break;
            }
        }
        return out;
    }

    static String extension(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        return dot > slash ? path.substring(dot + 1).toLowerCase() : path.substring(slash + 1).toLowerCase();
    }
}
