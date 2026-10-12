package com.saipramod.gitcontext.config;

import com.saipramod.gitcontext.answer.AnswerGenerator;
import com.saipramod.gitcontext.answer.ExtractiveAnswerGenerator;
import com.saipramod.gitcontext.answer.LlmAnswerGenerator;
import com.saipramod.gitcontext.index.Embedder;
import com.saipramod.gitcontext.index.HashingEmbedder;
import com.saipramod.gitcontext.index.SpringAiEmbedder;
import com.saipramod.gitcontext.retrieve.HeuristicReranker;
import com.saipramod.gitcontext.retrieve.LlmReranker;
import com.saipramod.gitcontext.retrieve.Reranker;
import com.saipramod.gitcontext.service.AskService;
import com.saipramod.gitcontext.service.CodebaseIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Wires the pipeline. Every model dependency is optional: with no API key the app uses
 * the offline hashing embedder, heuristic reranker and extractive answerer; set
 * LLM_PROVIDER=anthropic + ANTHROPIC_API_KEY and the same pipeline uses Claude.
 */
@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    Embedder embedder(ObjectProvider<EmbeddingModel> embeddingModel) {
        EmbeddingModel model = embeddingModel.getIfAvailable();
        return model != null ? new SpringAiEmbedder(model) : new HashingEmbedder();
    }

    @Bean
    Reranker reranker(ObjectProvider<ChatModel> chatModel, GitContextProperties props) {
        Reranker heuristic = new HeuristicReranker();
        ChatModel model = chatModel.getIfAvailable();
        if (model != null && props.llmReranker()) {
            return new LlmReranker(ChatClient.create(model), heuristic, 20);
        }
        return heuristic;
    }

    @Bean
    AnswerGenerator answerGenerator(ObjectProvider<ChatModel> chatModel) {
        ChatModel model = chatModel.getIfAvailable();
        return model != null ? new LlmAnswerGenerator(ChatClient.create(model)) : new ExtractiveAnswerGenerator();
    }

    @Bean
    CodebaseIndex codebaseIndex(Embedder embedder, Reranker reranker, GitContextProperties props) {
        return new CodebaseIndex(embedder, reranker, props.includeCommits());
    }

    @Bean
    AskService askService(CodebaseIndex index, AnswerGenerator generator, GitContextProperties props) {
        return new AskService(index, generator, props.minEvidence());
    }

    @Bean
    ApplicationRunner indexOnStartup(CodebaseIndex index, GitContextProperties props, AnswerGenerator generator) {
        return args -> {
            var stats = index.index(Path.of(props.repoPath()));
            log.info("Indexed {} files into {} chunks {} | embedder={} reranker={} answerer={}",
                    stats.files(), stats.chunks(), stats.chunksByKind(), stats.embedder(), stats.reranker(),
                    generator.name());
        };
    }
}
