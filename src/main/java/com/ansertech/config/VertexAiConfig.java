package com.ansertech.config;

import com.google.cloud.vertexai.VertexAI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Slf4j
@Configuration
public class VertexAiConfig {

    @Value("${vertex-ai.project-id}")
    private String projectId;

    @Value("${vertex-ai.location}")
    private String location;

    @Bean
    public VertexAI vertexAI() throws IOException {
        log.info("Inicializando Vertex AI — proyecto: {}, región: {}", projectId, location);
        return new VertexAI(projectId, location);
    }
}
