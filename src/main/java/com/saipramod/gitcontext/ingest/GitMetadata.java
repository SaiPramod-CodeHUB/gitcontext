package com.saipramod.gitcontext.ingest;

import com.saipramod.gitcontext.core.Chunk;
import com.saipramod.gitcontext.core.ChunkKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Reads git history with the git CLI (no JGit dependency): last commit per file for
 * citation metadata, and recent commits as their own retrievable chunks, so questions
 * like "why was retry logic changed" can be answered from commit messages.
 */
public class GitMetadata {

    private static final String SEP = "\u001f";
    private static final String REC = "@@commit@@";

    public record CommitInfo(String sha, String author, String date, String subject, String body, List<String> files) { }

    private final Path root;
    private final boolean available;

    public GitMetadata(Path root) {
        this.root = root;
        this.available = Files.isDirectory(root.resolve(".git")) && run("git", "--version") != null;
    }

    public boolean available() {
        return available;
    }

    public List<CommitInfo> recentCommits(int max) {
        if (!available) {
            return List.of();
        }
        String out = run("git", "-C", root.toString(), "log", "-n", String.valueOf(max), "--name-only",
                "--format=" + REC + "%H" + SEP + "%an" + SEP + "%cI" + SEP + "%s" + SEP + "%b" + SEP);
        if (out == null) {
            return List.of();
        }
        List<CommitInfo> commits = new ArrayList<>();
        for (String record : out.split(REC)) {
            if (record.isBlank()) {
                continue;
            }
            String[] parts = record.split(SEP, -1);
            if (parts.length < 6) {
                continue;
            }
            List<String> files = parts[5].lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
            commits.add(new CommitInfo(parts[0].strip(), parts[1], parts[2], parts[3], parts[4].strip(), files));
        }
        return commits;
    }

    /** Most recent commit touching each file. */
    public Map<String, CommitInfo> lastCommitPerFile(List<CommitInfo> commits) {
        Map<String, CommitInfo> last = new HashMap<>();
        for (CommitInfo c : commits) {           // newest first
            for (String f : c.files()) {
                last.putIfAbsent(f, c);
            }
        }
        return last;
    }

    public static Chunk toChunk(CommitInfo c) {
        String text = c.subject() + "\n\n" + c.body() + "\n\nFiles changed:\n" + String.join("\n", c.files());
        Map<String, String> meta = new HashMap<>();
        meta.put("commit", c.sha());
        meta.put("author", c.author());
        meta.put("lastModified", c.date());
        String path = "git:commit/" + c.sha().substring(0, Math.min(10, c.sha().length()));
        return new Chunk(path, path, ChunkKind.COMMIT, c.subject(), 0, 0, text.strip(), meta);
    }

    private static String run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] bytes = p.getInputStream().readAllBytes();
            if (!p.waitFor(20, TimeUnit.SECONDS) || p.exitValue() != 0) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
