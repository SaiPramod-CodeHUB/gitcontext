package com.saipramod.gitcontext.ingest;

import com.saipramod.gitcontext.core.Chunk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Walks a repository checkout and routes each file to the right chunker. Every chunk
 * carries file-level metadata (content hash, last commit, author, last modified) so
 * answers can cite it and the staleness monitor can detect changed files.
 */
public class RepoIngestor {

    public static final Set<String> SKIP_DIRS = Set.of(".git", "target", "build", "node_modules", ".idea",
            ".gradle", ".mvn", "dist", "out", ".venv", "__pycache__", "eval-results");
    private static final Set<String> TEXT_EXT = Set.of("java", "kt", "md", "yml", "yaml", "properties", "xml",
            "json", "sql", "sh", "py", "js", "ts", "txt", "toml", "conf", "gradle", "dockerfile", "html", "css");
    private static final long MAX_FILE_BYTES = 300_000;

    private final Set<String> skipDirs;
    private final JavaChunker javaChunker = new JavaChunker();
    private final MarkdownChunker markdownChunker = new MarkdownChunker();
    private final TextChunker textChunker = new TextChunker();

    public RepoIngestor() {
        this(Set.of());
    }

    /** @param extraSkipDirs additional directory names to ignore (e.g. the eval set itself) */
    public RepoIngestor(Set<String> extraSkipDirs) {
        Set<String> all = new java.util.HashSet<>(SKIP_DIRS);
        all.addAll(extraSkipDirs);
        this.skipDirs = Set.copyOf(all);
    }

    public record IngestResult(List<Chunk> chunks, Map<String, String> fileHashes, int files, int commits) { }

    public IngestResult ingest(Path root, boolean includeCommits) {
        GitMetadata git = new GitMetadata(root);
        List<GitMetadata.CommitInfo> commits = git.recentCommits(300);
        Map<String, GitMetadata.CommitInfo> lastCommit = git.lastCommitPerFile(commits);

        List<Chunk> chunks = new ArrayList<>();
        Map<String, String> hashes = new HashMap<>();
        for (Path file : listFiles(root)) {
            String rel = relative(root, file);
            String content = read(file);
            String hash = sha256(content);
            hashes.put(rel, hash);
            chunks.addAll(chunkFile(rel, content, fileMeta(hash, lastCommit.get(rel))));
        }
        if (includeCommits) {
            commits.forEach(c -> chunks.add(GitMetadata.toChunk(c)));
        }
        return new IngestResult(chunks, hashes, hashes.size(), includeCommits ? commits.size() : 0);
    }

    /** Re-chunk a single file (used by incremental re-indexing). */
    public List<Chunk> ingestFile(Path root, String relPath) {
        Path file = root.resolve(relPath);
        String content = read(file);
        return chunkFile(relPath, content, fileMeta(sha256(content), null));
    }

    public List<Chunk> chunkFile(String rel, String content, Map<String, String> meta) {
        String ext = TextChunker.extension(rel);
        List<Chunk> chunks = switch (ext) {
            case "java" -> javaChunker.chunk(rel, content, meta);
            case "md" -> markdownChunker.chunk(rel, content, meta);
            default -> List.of();
        };
        // Files the structural chunker could not split (e.g. package-info, empty enums) fall back to windows.
        return chunks.isEmpty() ? textChunker.chunk(rel, content, meta) : chunks;
    }

    public List<Path> listFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !inSkippedDir(root, p))
                    .filter(RepoIngestor::isIndexable)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String hashOf(Path file) {
        return sha256(read(file));
    }

    private static boolean isIndexable(Path p) {
        try {
            String name = p.getFileName().toString().toLowerCase();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
            return TEXT_EXT.contains(ext) && Files.size(p) <= MAX_FILE_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean inSkippedDir(Path root, Path p) {
        for (Path part : root.relativize(p)) {
            if (skipDirs.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> fileMeta(String hash, GitMetadata.CommitInfo commit) {
        Map<String, String> meta = new HashMap<>();
        meta.put("contentHash", hash);
        if (commit != null) {
            meta.put("commit", commit.sha());
            meta.put("author", commit.author());
            meta.put("lastModified", commit.date());
        }
        return meta;
    }

    static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
