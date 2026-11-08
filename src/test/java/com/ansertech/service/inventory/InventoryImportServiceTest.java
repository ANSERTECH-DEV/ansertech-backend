package com.ansertech.service.inventory;

import com.ansertech.domain.entity.Product;
import com.ansertech.dto.response.ImportResultResponse;
import com.ansertech.dto.response.ImportResultResponse.Action;
import com.ansertech.exception.BusinessException;
import com.ansertech.repository.ProductRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lógica de la carga masiva de inventario (parseo, validación, upsert por SKU, dryRun).
 * Los Excel se construyen en memoria con Apache POI usando los mismos encabezados que
 * genera GET /api/inventory/export.
 */
@ExtendWith(MockitoExtension.class)
class InventoryImportServiceTest {

    static final String[] EXPORT_HEADERS = {"ID", "SKU", "NOMBRE", "CATEGORÍA", "DESCRIPCIÓN",
            "UNIDAD", "STOCK", "STOCK MÍNIMO", "PRECIO UNIT.", "MONEDA", "PROVEEDOR", "MARCA", "ACTIVO"};

    @Mock private ProductRepository productRepository;
    private InventoryImportService service;
    private Product existing;

    @BeforeEach
    void setUp() {
        service = new InventoryImportService(productRepository);
        existing = Product.builder().id(1L).sku("BAL-001").name("Balanza analítica")
                .category("Balanzas").unit("unidad").brand("XY Scale")
                .stockQuantity(new BigDecimal("10.000")).minStockThreshold(new BigDecimal("5.000"))
                .unitPrice(new BigDecimal("100.00")).currency("PEN").active(true).build();
    }

    @Test
    void import_createsNewProducts_andUpdatesExisting() throws Exception {
        when(productRepository.findAll()).thenReturn(new ArrayList<>(List.of(existing)));
        InputStream xlsx = excel(
                row("1", "BAL-001", "Balanza analítica", "Balanzas", "", "unidad", "20", "5", "120", "PEN", "", "XY Scale", "Sí"),
                row("", "PHM-002", "Phmetro portátil", "Phmetros", "Mide pH", "unidad", "30", "", "250.5", "", "", "Hanna", ""));

        ImportResultResponse result = service.importProducts(xlsx, false);

        assertThat(result.isDryRun()).isFalse();
        assertThat(result.getCreated()).isEqualTo(1);
        assertThat(result.getUpdated()).isEqualTo(1);
        assertThat(result.getErrors()).isZero();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Product>> saved = ArgumentCaptor.forClass(List.class);
        verify(productRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2);

        assertThat(existing.getStockQuantity()).isEqualByComparingTo("20");
        assertThat(existing.getUnitPrice()).isEqualByComparingTo("120");

        Product created = saved.getValue().stream().filter(p -> p.getSku().equals("PHM-002")).findFirst().orElseThrow();
        assertThat(created.getUnitPrice()).isEqualByComparingTo("251");         // 250.5 → soles enteros (V10)
        assertThat(created.getMinStockThreshold()).isEqualByComparingTo("5");   // default
        assertThat(created.getCurrency()).isEqualTo("PEN");                     // default
        assertThat(created.getActive()).isTrue();                               // default
    }

    @Test
    void dryRun_doesNotSave_norMutateExistingEntity() throws Exception {
        when(productRepository.findAll()).thenReturn(new ArrayList<>(List.of(existing)));
        InputStream xlsx = excel(
                row("1", "BAL-001", "Balanza analítica", "", "", "", "20", "", "120", "", "", "", ""),
                row("", "PHM-002", "Phmetro portátil", "", "", "", "30", "", "250", "", "", "", ""));

        ImportResultResponse result = service.importProducts(xlsx, true);

        verify(productRepository, never()).saveAll(any());
        assertThat(existing.getStockQuantity()).isEqualByComparingTo("10");
        assertThat(existing.getUnitPrice()).isEqualByComparingTo("100");

        ImportResultResponse.Row update = result.getRows().get(0);
        assertThat(update.getAction()).isEqualTo(Action.UPDATE);
        assertThat(update.getOldStock()).isEqualByComparingTo("10");
        assertThat(update.getNewStock()).isEqualByComparingTo("20");
        assertThat(update.getOldPrice()).isEqualByComparingTo("100");
        assertThat(update.getNewPrice()).isEqualByComparingTo("120");
        assertThat(result.getRows().get(1).getAction()).isEqualTo(Action.CREATE);
    }

    @Test
    void blankCells_keepCurrentValues_andIdenticalRowIsUnchanged() throws Exception {
        when(productRepository.findAll()).thenReturn(new ArrayList<>(List.of(existing)));
        // Solo SKU + el mismo precio: nada cambia
        InputStream xlsx = excel(row("", "bal-001", "", "", "", "", "", "", "100", "", "", "", "Si"));

        ImportResultResponse result = service.importProducts(xlsx, false);

        assertThat(result.getUnchanged()).isEqualTo(1);
        assertThat(result.getRows().get(0).getName()).isEqualTo("Balanza analítica");
        assertThat(existing.getCategory()).isEqualTo("Balanzas");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Product>> saved = ArgumentCaptor.forClass(List.class);
        verify(productRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).isEmpty();
    }

    @Test
    void invalidRows_areReportedAndSkipped_validRowsStillImported() throws Exception {
        when(productRepository.findAll()).thenReturn(new ArrayList<>());
        InputStream xlsx = excel(
                row("", "OK-001", "Producto válido", "", "", "", "5", "", "10", "", "", "", ""),
                row("", "ERR-001", "", "", "", "", "5", "", "10", "", "", "", ""),            // sin nombre
                row("", "ERR-002", "Precio negativo", "", "", "", "5", "", "-3", "", "", "", ""),
                row("", "ERR-003", "Stock texto", "", "", "", "muchos", "", "10", "", "", "", ""),
                row("", "ERR-004", "Sin precio", "", "", "", "5", "", "", "", "", "", ""),
                row("", "ERR-005", "Activo raro", "", "", "", "5", "", "10", "", "", "", "quizás"),
                row("", "OK-001", "Duplicado", "", "", "", "5", "", "10", "", "", "", ""));

        ImportResultResponse result = service.importProducts(xlsx, false);

        assertThat(result.getTotalRows()).isEqualTo(7);
        assertThat(result.getCreated()).isEqualTo(1);
        assertThat(result.getErrors()).isEqualTo(6);
        List<String> messages = result.getRows().stream()
                .filter(r -> r.getAction() == Action.ERROR).map(ImportResultResponse.Row::getMessage).toList();
        assertThat(messages).anyMatch(m -> m.contains("NOMBRE es obligatorio"));
        assertThat(messages).anyMatch(m -> m.contains("PRECIO no puede ser negativo"));
        assertThat(messages).anyMatch(m -> m.contains("STOCK no es un número válido"));
        assertThat(messages).anyMatch(m -> m.contains("PRECIO es obligatorio"));
        assertThat(messages).anyMatch(m -> m.contains("ACTIVO debe ser Sí o No"));
        assertThat(messages).anyMatch(m -> m.contains("SKU repetido en el archivo (ya aparece en la fila 2)"));
    }

    @Test
    void blankRows_areIgnored() throws Exception {
        when(productRepository.findAll()).thenReturn(new ArrayList<>());
        InputStream xlsx = excel(
                row("", "", "", "", "", "", "", "", "", "", "", "", ""),
                row("", "NEW-1", "Nuevo", "", "", "", "", "", "5", "", "", "", ""));

        ImportResultResponse result = service.importProducts(xlsx, true);

        assertThat(result.getTotalRows()).isEqualTo(1);
        assertThat(result.getRows().get(0).getRowNumber()).isEqualTo(3);
    }

    @Test
    void missingRequiredColumn_isRejected() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Row header = wb.createSheet().createRow(0);
            header.createCell(0).setCellValue("NOMBRE");
            header.createCell(1).setCellValue("PRECIO");
            wb.write(out);

            assertThatThrownBy(() -> service.importProducts(new ByteArrayInputStream(out.toByteArray()), true))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("SKU");
        }
    }

    @Test
    void nonExcelFile_isRejectedWithBusinessException() {
        InputStream notExcel = new ByteArrayInputStream("sku,nombre\nA,B".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.importProducts(notExcel, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(".xlsx");
    }

    @Test
    void parseNumber_acceptsLocalFormats() {
        assertThat(InventoryImportService.parseNumber("1234.5")).isEqualByComparingTo("1234.5");
        assertThat(InventoryImportService.parseNumber("1234,5")).isEqualByComparingTo("1234.5");
        assertThat(InventoryImportService.parseNumber("1.234,50")).isEqualByComparingTo("1234.50");
        assertThat(InventoryImportService.parseNumber("1,234.50")).isEqualByComparingTo("1234.50");
        assertThat(InventoryImportService.parseNumber("1,234")).isEqualByComparingTo("1234");
        assertThat(InventoryImportService.parseNumber("S/ 1 234,50")).isEqualByComparingTo("1234.50");
        assertThat(InventoryImportService.parseNumber("  ")).isNull();
    }

    @Test
    void normalize_matchesExportHeaders() {
        assertThat(InventoryImportService.normalize("PRECIO UNIT.")).isEqualTo("PRECIO UNIT");
        assertThat(InventoryImportService.normalize("Stock Mínimo")).isEqualTo("STOCK MINIMO");
        assertThat(InventoryImportService.normalize("CATEGORÍA")).isEqualTo("CATEGORIA");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String[] row(String... cells) {
        return cells;
    }

    /** Construye un .xlsx con los encabezados del export; las celdas numéricas van como número. */
    static InputStream excel(String[]... rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Inventario");
            Row header = sheet.createRow(0);
            for (int i = 0; i < EXPORT_HEADERS.length; i++) header.createCell(i).setCellValue(EXPORT_HEADERS[i]);
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    String v = rows[r][c];
                    if (v.isEmpty()) continue;
                    if (v.matches("-?\\d+(\\.\\d+)?") && c >= 6 && c <= 8) {
                        row.createCell(c).setCellValue(Double.parseDouble(v));
                    } else {
                        row.createCell(c).setCellValue(v);
                    }
                }
            }
            wb.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }
}
