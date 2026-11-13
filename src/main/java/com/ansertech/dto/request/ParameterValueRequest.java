package com.ansertech.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ParameterValueRequest {
    @NotBlank
    @Size(max = 255)
    private String value;
}
