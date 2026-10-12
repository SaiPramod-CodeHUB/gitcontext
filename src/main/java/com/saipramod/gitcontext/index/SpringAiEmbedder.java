package com.saipramod.gitcontext.index;

import org.springframework.ai.embedding.EmbeddingModel;

/** Adapter for any Spring AI EmbeddingModel (OpenAI, Ollama, Bedrock, ONNX transformers...). */
public class SpringAiEmbedder implements Embedder {

    private final EmbeddingModel model;

    public SpringAiEmbedder(EmbeddingModel model) {
        this.model = model;
    }

    @Override
    public float[] embed(String text) {
        return Embedder.normalize(model.embed(text));
    }

    @Override
    public String name() {
        return "spring-ai:" + model.getClass().getSimpleName();
    }
}
