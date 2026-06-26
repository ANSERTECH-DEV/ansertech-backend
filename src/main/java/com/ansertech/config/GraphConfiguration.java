package com.ansertech.config;

import com.azure.identity.DeviceCodeCredential;
import com.azure.identity.DeviceCodeCredentialBuilder;
import com.azure.identity.TokenCachePersistenceOptions;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class GraphConfiguration {

    @Value("${microsoft.graph.client-id}")
    private String clientId;

    private final String tenantId = "consumers";

    @Bean
    public GraphServiceClient graphServiceClient() {
        TokenCachePersistenceOptions tokenCacheOptions = new TokenCachePersistenceOptions()
                .setName("ansertech-graph-cache");

        DeviceCodeCredential credential = new DeviceCodeCredentialBuilder()
                .clientId(clientId)
                .tenantId(tenantId)
                .tokenCachePersistenceOptions(tokenCacheOptions)
                .challengeConsumer(challenge -> {
                    log.warn("\n=======================================================\n" +
                             "REQUIRES AUTHENTICATION:\n" +
                             challenge.getMessage() + "\n" +
                             "=======================================================\n");
                })
                .build();

        String[] scopes = new String[]{
                "https://graph.microsoft.com/Mail.ReadWrite",
                "https://graph.microsoft.com/Mail.Send"
        };
        return new GraphServiceClient(credential, scopes);
    }
}
