package com.example.ekb.ai.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AiRagContractFixtureTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeJavaRequestUsingPythonFieldNames() throws Exception {
        JsonNode fixture = objectMapper.readTree(Files.readString(contract("rag-generate-request.json")));
        AiRagGenerateRequest request = objectMapper.treeToValue(fixture, AiRagGenerateRequest.class);

        JsonNode serialized = objectMapper.readTree(objectMapper.writeValueAsString(request));

        assertThat(serialized.equals(fixture)).isTrue();
        assertThat(request.contexts()).hasSize(2);
        assertThat(request.contexts().get(0).docId()).isEqualTo(33L);
    }

    @Test
    void shouldDeserializeStructuredPythonResponse() throws Exception {
        JsonNode fixture = objectMapper.readTree(Files.readString(contract("rag-generate-response.json")));

        AiRagGenerateResponse response = objectMapper.treeToValue(fixture, AiRagGenerateResponse.class);
        JsonNode serialized = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertThat(response.answerStatus()).isEqualTo("ANSWERED");
        assertThat(response.citedContextIndexes()).containsExactly(1);
        assertThat(response.noAnswerReason()).isNull();
        assertThat(serialized.equals(fixture)).isTrue();
    }

    private Path contract(String fileName) {
        Path fromModule = Path.of("..", "contracts", "ai", fileName).normalize();
        if (Files.isRegularFile(fromModule)) {
            return fromModule;
        }
        return Path.of("contracts", "ai", fileName);
    }
}
