## GitContext retrieval eval

Corpus: 43 files, 218 chunks | embedder: hashing-1024 | reranker: heuristic-cross-scorer | 40 questions + 8 out-of-scope

| Mode | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR | P@5 | Citation acc. | p50 ms | p95 ms |
|---|---|---|---|---|---|---|---|---|---|
| BM25 | 0.525 | 0.850 | **0.925** | 0.925 | 0.697 | 0.365 | 0.507 | 0.24 | 0.65 |
| VECTOR | 0.600 | 0.750 | **0.825** | 0.850 | 0.694 | 0.385 | 0.452 | 0.40 | 0.57 |
| HYBRID | 0.650 | 0.825 | **0.850** | 0.925 | 0.743 | 0.360 | 0.416 | 0.62 | 0.78 |
| HYBRID_RERANK | 0.650 | 0.850 | **0.950** | 0.975 | 0.762 | 0.375 | 0.482 | 6.73 | 12.92 |

Held-out half only (20 questions never looked at while tuning):

| Mode | Recall@1 | Recall@5 | MRR | Citation acc. |
|---|---|---|---|---|
| BM25 | 0.500 | **0.950** | 0.689 | 0.586 |
| VECTOR | 0.550 | **0.850** | 0.675 | 0.425 |
| HYBRID | 0.600 | **0.850** | 0.710 | 0.392 |
| HYBRID_RERANK | 0.600 | **1.000** | 0.741 | 0.500 |

Recall@5 by question type:

| Mode | identifier | natural | error |
|---|---|---|---|
| BM25 | 1.000 | 0.870 | 1.000 | 
| VECTOR | 1.000 | 0.826 | 0.400 | 
| HYBRID | 1.000 | 0.826 | 0.600 | 
| HYBRID_RERANK | 1.000 | 0.913 | 1.000 | 

Retrieval-failure guard: abstained on 75% of out-of-scope questions, false abstain on 3% of answerable ones.

HYBRID_RERANK misses (not in top 5):

- How does the offline embedding model turn text into a vector without a neural network? -> expected [src/main/java/com/saipramod/gitcontext/index/HashingEmbedder.java], got [src/main/java/com/saipramod/gitcontext/index/Embedder.java, src/main/java/com/saipramod/gitcontext/config/AppConfig.java, src/main/java/com/saipramod/gitcontext/index/SpringAiEmbedder.java]
- What happens when the language model call throws an exception while answering? -> expected [src/main/java/com/saipramod/gitcontext/service/AskService.java], got [src/main/java/com/saipramod/gitcontext/ingest/RepoIngestor.java, src/main/java/com/saipramod/gitcontext/config/AppConfig.java, src/main/java/com/saipramod/gitcontext/ingest/RepoIngestor.java]
