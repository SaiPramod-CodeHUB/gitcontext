package com.saipramod.gitcontext.retrieve;

import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Listwise LLM reranker: the model sees the query and the candidate snippets and returns
 * the ids in relevance order. Falls back to the input order (or a delegate) on any
 * parsing / API failure so retrieval never breaks because the reranker did.
 */
public class LlmReranker implements Reranker {

    private static final Pattern NUM = Pattern.compile("\\d+");
    private final ChatClient chat;
    private final Reranker fallback;
    private final int maxCandidates;

    public LlmReranker(ChatClient chat, Reranker fallback, int maxCandidates) {
        this.chat = chat;
        this.fallback = fallback;
        this.maxCandidates = maxCandidates;
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates) {
        List<RetrievedChunk> pool = candidates.subList(0, Math.min(maxCandidates, candidates.size()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pool.size(); i++) {
            var c = pool.get(i).chunk();
            String snippet = c.text().length() > 600 ? c.text().substring(0, 600) : c.text();
            sb.append("[").append(i).append("] ").append(c.location()).append(" ").append(c.symbol())
                    .append("\n").append(snippet).append("\n\n");
        }
        try {
            String reply = chat.prompt()
                    .system("You rank code search results. Reply ONLY with candidate numbers, most relevant first, comma separated.")
                    .user("Question: " + query + "\n\nCandidates:\n" + sb)
                    .call()
                    .content();
            Set<Integer> order = new LinkedHashSet<>();
            Matcher m = NUM.matcher(reply == null ? "" : reply);
            while (m.find()) {
                int idx = Integer.parseInt(m.group());
                if (idx >= 0 && idx < pool.size()) {
                    order.add(idx);
                }
            }
            for (int i = 0; i < pool.size(); i++) {
                order.add(i);
            }
            List<RetrievedChunk> out = new ArrayList<>();
            int rank = 0;
            for (int idx : order) {
                out.add(pool.get(idx).withScore(1.0 / (1 + rank++)));
            }
            return out;
        } catch (RuntimeException e) {
            return fallback.rerank(query, candidates);
        }
    }

    @Override
    public void attach(com.saipramod.gitcontext.index.Bm25Index index) {
        fallback.attach(index);
    }

    @Override
    public String name() {
        return "llm-listwise";
    }
}
