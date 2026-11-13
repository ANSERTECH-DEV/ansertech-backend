package com.ansertech.dto.response;

import com.ansertech.domain.entity.Parameter;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ParameterResponse {
    private final Long id;
    private final String name;
    private final String description;
    private final String value;
    private final LocalDateTime createdAt;

    public static ParameterResponse from(Parameter parameter) {
        return ParameterResponse.builder()
                .id(parameter.getId())
                .name(parameter.getName())
                .description(parameter.getDescription())
                .value(parameter.getValue())
                .createdAt(parameter.getCreatedAt())
                .build();
    }
}
