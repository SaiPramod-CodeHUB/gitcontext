package com.saipramod.gitcontext.service;

import com.saipramod.gitcontext.core.Chunk;
import com.saipramod.gitcontext.index.Bm25Index;
import com.saipramod.gitcontext.index.Embedder;
import com.saipramod.gitcontext.index.VectorIndex;
import com.saipramod.gitcontext.ingest.RepoIngestor;
import com.saipramod.gitcontext.retrieve.HybridRetriever;
import com.saipramod.gitcontext.retrieve.Reranker;
import com.saipramod.gitcontext.retrieve.RetrievalMode;
import com.saipramod.gitcontext.retrieve.RetrievedChunk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Owns the indexed state of one repository: chunks, keyword index, vector index and the
 * per-file content hashes used for staleness detection. Reads (search) and writes
 * (re-index) are guarded by a read/write lock so queries keep working during a refresh.
 */
public class CodebaseIndex {

    public record Stats(String root, int files, int chunks, int commits, Map<String, Long> chunksByKind,
                        String embedder, String reranker, Instant lastIndexed) { }

    public record ReindexReport(List<String> changed, List<String> added, List<String> deleted,
                                int chunksBefore, int chunksAfter, long millis) {
        public boolean noop() {
            return changed.isEmpty() && added.isEmpty() && deleted.isEmpty();
        }
    }

    private final RepoIngestor ingestor;
    private final Bm25Index bm25 = new Bm25Index();
    private final VectorIndex vectors;
    private final HybridRetriever retriever;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final boolean includeCommits;

    private Path root;
    private final Map<String, Chunk> chunksById = new LinkedHashMap<>();
    private final Map<String, List<String>> chunkIdsByFile = new HashMap<>();
    private Map<String, String> fileHashes = new HashMap<>();
    private int commitCount;
    private Instant lastIndexed;

    public CodebaseIndex(Embedder embedder, Reranker reranker, boolean includeCommits) {
        this(embedder, reranker, includeCommits, Set.of());
    }

    public CodebaseIndex(Embedder embedder, Reranker reranker, boolean includeCommits, Set<String> extraSkipDirs) {
        this.ingestor = new RepoIngestor(extraSkipDirs);
        this.vectors = new VectorIndex(embedder);
        this.includeCommits = includeCommits;
        this.retriever = new HybridRetriever(bm25, vectors, reranker, chunksById::get, 50, 30);
    }

    public Stats index(Path repoRoot) {
        lock.writeLock().lock();
        try {
            this.root = repoRoot.toAbsolutePath().normalize();
            RepoIngestor.IngestResult result = ingestor.ingest(root, includeCommits);
            chunksById.clear();
            chunkIdsByFile.clear();
            for (Chunk c : result.chunks()) {
                chunksById.put(c.id(), c);
                chunkIdsByFile.computeIfAbsent(c.path(), k -> new ArrayList<>()).add(c.id());
            }
            fileHashes = new HashMap<>(result.fileHashes());
            commitCount = result.commits();
            bm25.build(new ArrayList<>(chunksById.values()));
            vectors.build(new ArrayList<>(chunksById.values()));
            lastIndexed = Instant.now();
            return stats();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Incremental refresh: hash every file, re-chunk and re-embed only files whose content
     * changed, drop chunks of deleted files. Embedding is the expensive step, so this keeps
     * a refresh proportional to the size of the change, not of the repository.
     */
    public ReindexReport reindexChanged() {
        if (root == null) {
            throw new IllegalStateException("Nothing indexed yet");
        }
        long start = System.nanoTime();
        lock.writeLock().lock();
        try {
            int before = chunksById.size();
            Map<String, String> current = new HashMap<>();
            for (Path p : ingestor.listFiles(root)) {
                current.put(root.relativize(p).toString().replace('\\', '/'), RepoIngestor.hashOf(p));
            }
            List<String> changed = new ArrayList<>();
            List<String> added = new ArrayList<>();
            List<String> deleted = new ArrayList<>();
            current.forEach((file, hash) -> {
                String old = fileHashes.get(file);
                if (old == null) {
                    added.add(file);
                } else if (!old.equals(hash)) {
                    changed.add(file);
                }
            });
            Set<String> gone = new HashSet<>(fileHashes.keySet());
            gone.removeAll(current.keySet());
            deleted.addAll(gone);

            for (String file : concat(changed, deleted)) {
                for (String id : chunkIdsByFile.getOrDefault(file, List.of())) {
                    chunksById.remove(id);
                    vectors.remove(id);
                }
                chunkIdsByFile.remove(file);
            }
            for (String file : concat(changed, added)) {
                if (!Files.exists(root.resolve(file))) {
                    continue;
                }
                for (Chunk c : ingestor.ingestFile(root, file)) {
                    chunksById.put(c.id(), c);
                    chunkIdsByFile.computeIfAbsent(file, k -> new ArrayList<>()).add(c.id());
                    vectors.upsert(c);
                }
            }
            if (!(changed.isEmpty() && added.isEmpty() && deleted.isEmpty())) {
                bm25.build(new ArrayList<>(chunksById.values()));   // BM25 rebuild is cheap (no model calls)
                lastIndexed = Instant.now();
            }
            fileHashes = current;
            changed.sort(null);
            added.sort(null);
            deleted.sort(null);
            return new ReindexReport(changed, added, deleted, before, chunksById.size(),
                    (System.nanoTime() - start) / 1_000_000);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<RetrievedChunk> search(String query, int k, RetrievalMode mode) {
        lock.readLock().lock();
        try {
            return retriever.retrieve(query, k, mode);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Retrieval-failure guard: true when neither retriever found meaningful evidence, so the
     * caller should say "not found" instead of letting an LLM improvise.
     */
    public boolean hasEvidence(String query, double minEvidence) {
        return evidenceScore(query) >= minEvidence;
    }

    /**
     * Keyword coverage (share of the question's idf mass found in one top hit) plus the best
     * dense cosine. Either signal alone is noisy; their sum separates answerable from
     * out-of-scope questions much better (threshold calibrated on the dev half of the eval set).
     */
    public double evidenceScore(String query) {
        double[] s = evidenceSignals(query);
        return s[0] + s[1];
    }

    /** Raw guard signals, exposed for threshold calibration. */
    public double[] evidenceSignals(String query) {
        lock.readLock().lock();
        try {
            var dense = vectors.search(query, 1);
            return new double[] {bm25.bestCoverage(query, 5), dense.isEmpty() ? 0 : dense.get(0).score()};
        } finally {
            lock.readLock().unlock();
        }
    }

    public Stats stats() {
        lock.readLock().lock();
        try {
            Map<String, Long> byKind = new java.util.TreeMap<>();
            chunksById.values().forEach(c -> byKind.merge(c.kind().name(), 1L, Long::sum));
            return new Stats(root == null ? null : root.toString(), fileHashes.size(), chunksById.size(), commitCount,
                    byKind, vectors.embedderName(), retriever.rerankerName(), lastIndexed);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Chunk get(String id) {
        return chunksById.get(id);
    }

    public Path root() {
        return root;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
