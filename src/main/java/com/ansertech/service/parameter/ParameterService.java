package com.ansertech.service.parameter;

import com.ansertech.domain.entity.Parameter;
import com.ansertech.exception.BusinessException;
import com.ansertech.exception.ResourceNotFoundException;
import com.ansertech.repository.ParameterRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class ParameterService {

    /** Correos no leídos que consulta el polling manual en la bandeja principal. */
    public static final String EMAIL_COUNT = "Cantidad de correos";

    private static final int MAX_EMAIL_COUNT = 1000;

    private final ParameterRepository parameterRepository;
    private final int defaultEmailCount;

    public ParameterService(ParameterRepository parameterRepository,
                            @Value("${microsoft.polling.max-messages-per-poll:2}") int defaultEmailCount) {
        this.parameterRepository = parameterRepository;
        this.defaultEmailCount = defaultEmailCount;
    }

    public List<Parameter> findAll() {
        return parameterRepository.findAll(Sort.by("id"));
    }

    /** Valor efectivo de {@link #EMAIL_COUNT}, con el de {@code max-messages-per-poll} si falta o no es válido. */
    public int getEmailCount() {
        return getEmailCount(defaultEmailCount);
    }

    /** Valor de {@link #EMAIL_COUNT} (1..1000); usa {@code fallback} si falta o no es válido. */
    public int getEmailCount(int fallback) {
        Optional<Parameter> parameter = parameterRepository.findByName(EMAIL_COUNT);
        if (parameter.isEmpty()) {
            log.warn("No existe el parámetro '{}': se usa el valor por defecto {}", EMAIL_COUNT, fallback);
            return fallback;
        }
        String value = parameter.get().getValue();
        Optional<Integer> count = parseEmailCount(value);
        if (count.isPresent()) {
            return count.get();
        }
        log.warn("Valor inválido '{}' en el parámetro '{}' (debe estar entre 1 y {}): se usa {}",
                value, EMAIL_COUNT, MAX_EMAIL_COUNT, fallback);
        return fallback;
    }

    /** Cambia solo el valor. "Cantidad de correos" debe ser un entero de 1 a 1000. */
    @Transactional
    public Parameter updateValue(Long id, String rawValue) {
        Parameter parameter = parameterRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Parámetro", id));
        String value = rawValue.trim();
        if (value.isEmpty()) {
            throw new BusinessException("El valor no puede estar vacío");
        }
        if (EMAIL_COUNT.equals(parameter.getName()) && parseEmailCount(value).isEmpty()) {
            throw new BusinessException("'" + EMAIL_COUNT + "' debe ser un número entero entre 1 y " + MAX_EMAIL_COUNT);
        }
        parameter.setValue(value);
        return parameterRepository.save(parameter);
    }

    private static Optional<Integer> parseEmailCount(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            int count = Integer.parseInt(value.trim());
            return count >= 1 && count <= MAX_EMAIL_COUNT ? Optional.of(count) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
