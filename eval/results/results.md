## GitContext retrieval eval

Corpus: 40 files, 203 chunks | embedder: hashing-1024 | reranker: heuristic-cross-scorer | 40 questions + 8 out-of-scope

| Mode | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR | P@5 | Citation acc. | p50 ms | p95 ms |
|---|---|---|---|---|---|---|---|---|---|
| BM25 | 0.600 | 0.875 | **0.925** | 0.950 | 0.745 | 0.375 | 0.449 | 0.25 | 0.39 |
| VECTOR | 0.625 | 0.750 | **0.825** | 0.850 | 0.706 | 0.390 | 0.423 | 0.50 | 0.57 |
| HYBRID | 0.675 | 0.825 | **0.850** | 0.925 | 0.754 | 0.365 | 0.410 | 0.53 | 0.63 |
| HYBRID_RERANK | 0.700 | 0.850 | **0.950** | 0.975 | 0.787 | 0.385 | 0.436 | 6.49 | 13.80 |

Held-out half only (20 questions never looked at while tuning):

| Mode | Recall@1 | Recall@5 | MRR | Citation acc. |
|---|---|---|---|---|
| BM25 | 0.550 | **0.950** | 0.717 | 0.461 |
| VECTOR | 0.550 | **0.850** | 0.675 | 0.368 |
| HYBRID | 0.650 | **0.850** | 0.735 | 0.342 |
| HYBRID_RERANK | 0.650 | **1.000** | 0.766 | 0.434 |

Recall@5 by question type:

| Mode | identifier | natural | error |
|---|---|---|---|
| BM25 | 1.000 | 0.870 | 1.000 | 
| VECTOR | 1.000 | 0.826 | 0.400 | 
| HYBRID | 1.000 | 0.826 | 0.600 | 
| HYBRID_RERANK | 1.000 | 0.913 | 1.000 | 

Retrieval-failure guard: abstained on 75% of out-of-scope questions, false abstain on 3% of answerable ones.

HYBRID_RERANK misses (not in top 5):

- How does the offline embedding model turn text into a vector without a neural network? -> expected [src/main/java/com/saipramod/gitcontext/index/HashingEmbedder.java], got [src/main/java/com/saipramod/gitcontext/config/AppConfig.java, src/main/java/com/saipramod/gitcontext/index/Embedder.java, src/main/java/com/saipramod/gitcontext/index/SpringAiEmbedder.java]
- What happens when the language model call throws an exception while answering? -> expected [src/main/java/com/saipramod/gitcontext/service/AskService.java], got [src/main/java/com/saipramod/gitcontext/config/AppConfig.java, src/main/java/com/saipramod/gitcontext/ingest/RepoIngestor.java, src/main/java/com/saipramod/gitcontext/ingest/RepoIngestor.java]
