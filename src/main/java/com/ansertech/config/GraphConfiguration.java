package com.ansertech.config;

import com.ansertech.service.graph.GraphConnectionState;
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

    /** Prefijo fijo de la línea de log; {@code LogReaderService} lo busca con una regex. */
    public static final String DEVICE_CODE_LOG_TAG = "[MICROSOFT_DEVICE_CODE]";

    /** Permisos delegados que usa el backend: leer/marcar correos y enviar cotizaciones. */
    public static final String[] GRAPH_SCOPES = {
            "https://graph.microsoft.com/Mail.ReadWrite",
            "https://graph.microsoft.com/Mail.Send"
    };

    @Value("${microsoft.graph.client-id}")
    private String clientId;

    //@Value("${microsoft.graph.tenant-id:consumers}")
    private final String tenantId = "consumers";

    /**
     * En Linux (contenedor de Cloud Run) la caché cifrada necesita libsecret, que la imagen alpine no trae.
     * Con {@code true} la caché se guarda sin cifrar en el disco del contenedor (que es efímero).
     */
    @Value("${microsoft.graph.allow-unencrypted-cache:false}")
    private boolean allowUnencryptedCache;

    @Bean
    public DeviceCodeCredential deviceCodeCredential(GraphConnectionState connectionState) {
        TokenCachePersistenceOptions tokenCacheOptions = new TokenCachePersistenceOptions()
                .setName("ansertech-graph-cache")
                .setUnencryptedStorageAllowed(allowUnencryptedCache);

        return new DeviceCodeCredentialBuilder()
                .clientId(clientId)
                .tenantId(tenantId)
                .tokenCachePersistenceOptions(tokenCacheOptions)
                .challengeConsumer(challenge -> {
                    // azure-identity solo lo imprime en System.out; aquí va al log (consola + archivo)
                    // y al estado en memoria que lee el portal.
                    connectionState.onChallenge(challenge);
                    log.warn("{} URL: {} | CODE: {} | EXPIRA: {} | MSG: {}",
                            DEVICE_CODE_LOG_TAG,
                            challenge.getVerificationUrl(),
                            challenge.getUserCode(),
                            challenge.getExpiresOn(),
                            challenge.getMessage());
                })
                .build();
    }

    @Bean
    public GraphServiceClient graphServiceClient(DeviceCodeCredential credential) {
        return new GraphServiceClient(credential, GRAPH_SCOPES);
    }
}
