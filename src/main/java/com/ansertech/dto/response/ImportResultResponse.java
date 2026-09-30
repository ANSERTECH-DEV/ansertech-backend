package com.ansertech.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultado de la carga masiva de inventario. Con dryRun=true describe lo que PASARÍA
 * (nada se guarda); con dryRun=false describe lo que efectivamente se guardó.
 */
@Data @Builder
public class ImportResultResponse {

    private boolean dryRun;
    private int totalRows;      // filas con datos (sin contar cabecera ni filas vacías)
    private int created;
    private int updated;
    private int unchanged;      // SKU existente sin ningún cambio
    private int errors;         // filas omitidas por error de validación
    private List<Row> rows;

    public enum Action { CREATE, UPDATE, UNCHANGED, ERROR }

    @Data @Builder
    public static class Row {
        private int rowNumber;          // número de fila en Excel (1 = cabecera)
        private String sku;
        private String name;
        private String brand;
        private Action action;
        private BigDecimal oldStock;    // null si el producto es nuevo
        private BigDecimal newStock;
        private BigDecimal oldPrice;    // null si el producto es nuevo
        private BigDecimal newPrice;
        private String message;         // motivo del error, si action = ERROR
    }
}
