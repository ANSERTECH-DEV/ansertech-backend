package com.ansertech.service.audit;

import com.ansertech.domain.entity.AuditLog;
import com.ansertech.domain.entity.User;
import com.ansertech.domain.enums.AuditAction;
import com.ansertech.repository.AuditLogRepository;
import com.ansertech.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registra en {@code audit_logs} quién hizo qué, sobre qué entidad y cuándo.
 *
 * <ul>
 *   <li>El usuario se toma del token JWT de la petición en curso; las acciones del pipeline
 *       automático (hilos sin petición) quedan como {@value #SYSTEM}.</li>
 *   <li>{@code details} es un JSON con el actor, la IP de origen y los datos propios de la acción.</li>
 *   <li>Cada registro se guarda en su propia transacción (REQUIRES_NEW): queda aunque la operación
 *       principal falle (p. ej. un login rechazado), y si la auditoría falla solo se escribe en el log,
 *       nunca interrumpe la operación del usuario.</li>
 * </ul>
 */
@Slf4j
@Service
public class AuditService {

    public static final String SYSTEM = "SISTEMA";

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;

    public AuditService(AuditLogRepository auditLogRepository, UserRepository userRepository,
                        ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Acción hecha por el usuario autenticado de la petición en curso. */
    public void record(AuditAction action, String entityType, Long entityId, Map<String, Object> details) {
        recordAs(currentUserEmail(), action, entityType, entityId, details);
    }

    /** Acción hecha por el pipeline automático, sin usuario. */
    public void recordSystem(AuditAction action, String entityType, Long entityId, Map<String, Object> details) {
        recordAs(null, action, entityType, entityId, details);
    }

    /**
     * Acción atribuida a un correo concreto (login, registro), cuando aún no hay usuario en el
     * contexto de seguridad. {@code actorEmail = null} equivale a {@value #SYSTEM}.
     */
    public void recordAs(String actorEmail, AuditAction action, String entityType, Long entityId,
                         Map<String, Object> details) {
        try {
            requiresNew.executeWithoutResult(status -> {
                User user = actorEmail == null ? null : userRepository.findByEmail(actorEmail).orElse(null);

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("actor", actorEmail != null ? actorEmail : SYSTEM);
                String ip = clientIp();
                if (ip != null) payload.put("ip", ip);
                if (details != null) payload.putAll(details);

                auditLogRepository.save(AuditLog.builder()
                        .action(action.name())
                        .entityType(entityType)
                        .entityId(entityId)
                        .user(user)
                        .details(toJson(payload))
                        .build());
            });
        } catch (Exception e) {
            log.warn("No se pudo registrar la auditoría {} sobre {}#{}: {}",
                    action, entityType, entityId, e.getMessage());
        }
    }

    /** Arma el mapa de detalles a partir de pares clave/valor; admite valores null. */
    public static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    /** Correo del usuario autenticado, o null si la acción no viene de un usuario. */
    public static String currentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return auth.getName();
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return payload.toString();
        }
    }

    private static String clientIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
