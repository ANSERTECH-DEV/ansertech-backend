package com.ansertech.service.parameter;

import com.ansertech.domain.entity.Parameter;
import com.ansertech.repository.ParameterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParameterService {

    /** Correos no leídos que consulta el polling manual en la bandeja principal. */
    public static final String EMAIL_COUNT = "Cantidad de correos";

    private static final int MAX_EMAIL_COUNT = 1000;

    private final ParameterRepository parameterRepository;

    /** Valor de {@link #EMAIL_COUNT} (1..1000); usa {@code fallback} si falta o no es válido. */
    public int getEmailCount(int fallback) {
        Optional<Parameter> parameter = parameterRepository.findByName(EMAIL_COUNT);
        if (parameter.isEmpty()) {
            log.warn("No existe el parámetro '{}': se usa el valor por defecto {}", EMAIL_COUNT, fallback);
            return fallback;
        }
        String value = parameter.get().getValue();
        try {
            int count = Integer.parseInt(value.trim());
            if (count >= 1 && count <= MAX_EMAIL_COUNT) {
                return count;
            }
        } catch (NumberFormatException ignored) {
            // se informa abajo, igual que un número fuera de rango
        }
        log.warn("Valor inválido '{}' en el parámetro '{}' (debe estar entre 1 y {}): se usa {}",
                value, EMAIL_COUNT, MAX_EMAIL_COUNT, fallback);
        return fallback;
    }
}
