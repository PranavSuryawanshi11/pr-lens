package com.bot.bot.llm;

import com.google.gson.Gson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatibleClientTest {

    @Test
    @DisplayName("Returns 'No diff content to review' for null or blank prompt without calling API")
    void returnsNoDiffContentForEmptyPrompt() {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient("http://localhost:8000", "key", "model");

        StepVerifier.create(client.generateCodeReview(null))
                .expectNext("No diff content to review")
                .verifyComplete();

        StepVerifier.create(client.generateCodeReview("   "))
                .expectNext("No diff content to review")
                .verifyComplete();
    }

    @Test
    @DisplayName("Parses standard chat completion response successfully")
    void parsesChatCompletionResponse() {
        String responseJson = """
                {
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "LGTM. Code is well structured."
                      }
                    }
                  ]
                }
                """;

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    assertEquals("http://api.test/v1/chat/completions", request.url().toString());
                    assertEquals("Bearer test-api-key", request.headers().getFirst("Authorization"));
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body(responseJson)
                            .build());
                })
                .build();

        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                "http://api.test", "test-api-key", "gpt-4o-mini", 10, webClient, new Gson()
        );

        StepVerifier.create(client.generateCodeReview("review this diff"))
                .expectNext("LGTM. Code is well structured.")
                .verifyComplete();
    }

    @Test
    @DisplayName("Returns empty string when choices array is empty")
    void handlesEmptyChoicesArray() {
        String responseJson = "{\"choices\": []}";

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .body(responseJson)
                        .build()))
                .build();

        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                "http://api.test", "key", "model", 10, webClient, new Gson()
        );

        StepVerifier.create(client.generateCodeReview("diff"))
                .expectNext("")
                .verifyComplete();
    }

    @Test
    @DisplayName("Returns empty string when content element is null")
    void handlesNullContentElement() {
        String responseJson = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": null
                      }
                    }
                  ]
                }
                """;

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .body(responseJson)
                        .build()))
                .build();

        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                "http://api.test", "key", "model", 10, webClient, new Gson()
        );

        StepVerifier.create(client.generateCodeReview("diff"))
                .expectNext("")
                .verifyComplete();
    }

    @Test
    @DisplayName("Returns 'Error processing LLM response' when response JSON is malformed")
    void handlesMalformedJsonResponse() {
        String malformedJson = "{ unquoted_key: not valid json";

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .body(malformedJson)
                        .build()))
                .build();

        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                "http://api.test", "key", "model", 10, webClient, new Gson()
        );

        StepVerifier.create(client.generateCodeReview("diff"))
                .expectNext("Error processing LLM response")
                .verifyComplete();
    }

    @Test
    @DisplayName("Propagates Mono.error when network or HTTP error occurs")
    void propagatesNetworkError() {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new RuntimeException("API connection timeout")))
                .build();

        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                "http://api.test", "key", "model", 10, webClient, new Gson()
        );

        StepVerifier.create(client.generateCodeReview("diff"))
                .expectErrorMatches(err -> err.getMessage().contains("API connection timeout"))
                .verify();
    }

    @Test
    @DisplayName("Primary 3-arg constructor initializes default settings")
    void primaryConstructorInitializesCorrectly() {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient("http://localhost:11434", "key", "llama3");
        assertNotNull(client);
    }
}
