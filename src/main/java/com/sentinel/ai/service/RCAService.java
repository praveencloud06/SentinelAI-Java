package com.sentinel.ai.service;

import com.sentinel.ai.dto.RCAResponse;
import com.sentinel.ai.model.KnowledgeBaseEntry;
import com.sentinel.ai.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RCAService {
    private static final Logger logger = LoggerFactory.getLogger(RCAService.class);
    private final ChatClient chatClient;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final SemanticSearchService semanticSearchService;

    /**
     * Runs a Root Cause Analysis (RCA) workflow using an LLM (Ollama chat model).
     *
     * <p>This uses a simple form of <b>RAG (Retrieval-Augmented Generation)</b>:</p>
     * <ol>
     *   <li><b>Retrieve</b>: find the most similar past logs (semantic search) and use their resolutions as context</li>
     *   <li><b>Generate</b>: ask the LLM to produce a structured JSON RCA using the provided context</li>
     * </ol>
     *
     * <p><b>When does this call Ollama?</b></p>
     * <ul>
     *   <li>SemanticSearchService will call Ollama embeddings once for the input log (query embedding)</li>
     *   <li>This method calls the Ollama chat model once via {@link ChatClient} to produce the RCA JSON</li>
     * </ul>
     *
     * <p><b>Why ask for JSON?</b>
     * We want a predictable output contract for downstream usage (UI / Jira ticket / reports). In production,
     * you'd typically enforce JSON format more strictly (e.g., a structured output converter or schema).</p>
     */
    public RCAResponse analyze(String log) {
        logger.info("RCA analysis for log");

        // 1) RETRIEVE: get the top 3 most similar logs to build context (past incidents).
        // This uses embeddings + cosine similarity (semantic similarity).
        List<KnowledgeBaseEntry> similar = semanticSearchService.search(log, 3).stream()
                .map(result -> knowledgeBaseRepository.findAll().stream()
                        .filter(e -> e.getLogText().equals(result.getLogText()))
                        .findFirst().orElse(null))
                .filter(e -> e != null)
                .collect(Collectors.toList());

        StringBuilder context = new StringBuilder();
        for (KnowledgeBaseEntry entry : similar) {
            context.append("Log: ").append(entry.getLogText()).append("\n");
            context.append("Resolution: ").append(entry.getResolutionNotes()).append("\n---\n");
        }

        // 2) GENERATE: create an instruction prompt for the LLM.
        // We include: (a) the log to analyze, (b) similar incidents + resolutions.
        // Business intent: LLM proposes RCA based on patterns seen in previous incidents (and general knowledge),
        // while grounding the response with your own historical context.
        String promptText = "Given the following log and similar past incidents, analyze and return a JSON with keys: issue, rootCause, impactedService, recommendedFix.\n" +
                "Log to analyze: " + log + "\n" +
                "Similar incidents:\n" + context;

        Prompt prompt = new PromptTemplate(promptText).create();

        // AI call: this executes a chat completion against the configured Ollama chat model (e.g., llama3).
        // - prompt(...) sets the messages/context
        // - call() executes the request
        // - content() returns the model's generated text
        String response = chatClient.prompt(prompt).call().content();

        // For MVP, parse manually (should use a JSON parser in production).
        return parseRCAResponse(response);
    }

    private RCAResponse parseRCAResponse(String response) {
        // Very basic parsing for MVP
        String issue = extractField(response, "issue");
        String rootCause = extractField(response, "rootCause");
        String impactedService = extractField(response, "impactedService");
        String recommendedFix = extractField(response, "recommendedFix");
        return new RCAResponse(issue, rootCause, impactedService, recommendedFix);
    }

    private String extractField(String json, String field) {
        String pattern = "\"" + field + "\"\s*:\s*\"(.*?)\"";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }
}
