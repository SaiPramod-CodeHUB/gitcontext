package com.saipramod.gitcontext.api;

import com.saipramod.gitcontext.answer.Answer;
import com.saipramod.gitcontext.retrieve.RetrievalMode;
import com.saipramod.gitcontext.service.AskService;
import com.saipramod.gitcontext.service.CodebaseIndex;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AskController {

    public record AskRequest(@NotBlank String question,
                             @Min(1) @Max(20) Integer k,
                             RetrievalMode mode) { }

    public record IndexRequest(@NotBlank String path) { }

    public record SearchHit(String location, String kind, String symbol, double score, int bm25Rank, int vectorRank) { }

    private final AskService askService;
    private final CodebaseIndex index;

    public AskController(AskService askService, CodebaseIndex index) {
        this.askService = askService;
        this.index = index;
    }

    @PostMapping("/ask")
    public Answer ask(@Valid @RequestBody AskRequest req) {
        return askService.ask(req.question(), req.k() == null ? 5 : req.k(),
                req.mode() == null ? RetrievalMode.HYBRID_RERANK : req.mode());
    }

    @GetMapping("/search")
    public List<SearchHit> search(@RequestParam String q,
                                  @RequestParam(defaultValue = "10") int k,
                                  @RequestParam(defaultValue = "HYBRID_RERANK") RetrievalMode mode) {
        return index.search(q, Math.min(k, 50), mode).stream()
                .map(r -> new SearchHit(r.chunk().location(), r.chunk().kind().name(), r.chunk().symbol(),
                        r.score(), r.bm25Rank(), r.vectorRank()))
                .toList();
    }

    @PostMapping("/index")
    public CodebaseIndex.Stats reindexAll(@Valid @RequestBody IndexRequest req) {
        Path p = Path.of(req.path());
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException("Not a directory: " + req.path());
        }
        return index.index(p);
    }

    @PostMapping("/reindex")
    public CodebaseIndex.ReindexReport reindexChanged() {
        return index.reindexChanged();
    }

    @GetMapping("/stats")
    public CodebaseIndex.Stats stats() {
        return index.stats();
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
