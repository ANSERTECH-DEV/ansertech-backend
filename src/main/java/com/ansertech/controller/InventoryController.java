package com.ansertech.controller;

import com.ansertech.domain.enums.AuditAction;
import com.ansertech.service.audit.AuditService;

import com.ansertech.domain.entity.Product;
import com.ansertech.dto.request.ProductRequest;
import com.ansertech.dto.request.StockUpdateRequest;
import com.ansertech.dto.response.ApiResponse;
import com.ansertech.dto.response.ImportResultResponse;
import com.ansertech.dto.response.ProductResponse;
import com.ansertech.exception.BusinessException;
import com.ansertech.repository.ProductRepository;
import com.ansertech.service.inventory.InventoryImportService;
import com.ansertech.service.inventory.InventoryService;
import com.ansertech.service.inventory.InventoryTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
@Tag(name = "Inventario", description = "Gestión de productos e inventario")
public class InventoryController {

    private final InventoryService inventoryService;
    private final InventoryImportService inventoryImportService;
    private final InventoryTemplateService inventoryTemplateService;
    private final ProductRepository productRepository;
    private final AuditService auditService;

    @GetMapping
    @Operation(summary = "Buscar productos con filtros")
    public ResponseEntity<ApiResponse<Page<ProductResponse>>> search(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(inventoryService.search(search, category, pageable)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtener producto por ID")
    public ResponseEntity<ApiResponse<ProductResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(inventoryService.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Crear nuevo producto")
    public ResponseEntity<ApiResponse<ProductResponse>> create(@Valid @RequestBody ProductRequest req) {
        ProductResponse created = inventoryService.create(req);
        auditService.record(AuditAction.PRODUCT_CREATED, "PRODUCT", created.getId(),
                AuditService.details("sku", created.getSku(), "nombre", created.getName(),
                        "precio", created.getUnitPrice(), "stock", created.getStockQuantity()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created, "Producto creado"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Actualizar producto")
    public ResponseEntity<ApiResponse<ProductResponse>> update(
            @PathVariable Long id, @Valid @RequestBody ProductRequest req) {
        ProductResponse before = inventoryService.getById(id);
        ProductResponse after = inventoryService.update(id, req);
        Map<String, Object> changes = productChanges(before, after);
        if (!changes.isEmpty()) {
            auditService.record(AuditAction.PRODUCT_UPDATED, "PRODUCT", id,
                    AuditService.details("sku", after.getSku(), "cambios", changes));
        }
        return ResponseEntity.ok(ApiResponse.ok(after));
    }

    @PatchMapping("/{id}/stock")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Actualizar stock de un producto")
    public ResponseEntity<ApiResponse<ProductResponse>> updateStock(
            @PathVariable Long id, @Valid @RequestBody StockUpdateRequest req) {
        ProductResponse before = inventoryService.getById(id);
        ProductResponse after = inventoryService.updateStock(id, req);
        auditService.record(AuditAction.PRODUCT_STOCK_UPDATED, "PRODUCT", id,
                AuditService.details("sku", after.getSku(), "stockAnterior", before.getStockQuantity(),
                        "stockNuevo", after.getStockQuantity(), "motivo", req.getReason()));
        return ResponseEntity.ok(ApiResponse.ok(after, "Stock actualizado"));
    }

    @GetMapping("/low-stock")
    @Operation(summary = "Listar productos con stock bajo")
    public ResponseEntity<ApiResponse<List<ProductResponse>>> getLowStock() {
        return ResponseEntity.ok(ApiResponse.ok(inventoryService.getLowStock()));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Carga masiva desde Excel (mismo formato que /export). dryRun=true solo previsualiza")
    public ResponseEntity<ApiResponse<ImportResultResponse>> importExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "true") boolean dryRun) throws Exception {
        if (file.isEmpty()) {
            throw new BusinessException("El archivo está vacío");
        }
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        if (!filename.endsWith(".xlsx")) {
            throw new BusinessException("Formato no soportado: sube un archivo Excel .xlsx");
        }
        ImportResultResponse result = inventoryImportService.importProducts(file.getInputStream(), dryRun);
        if (!dryRun) {
            auditService.record(AuditAction.INVENTORY_IMPORTED, "INVENTORY", null,
                    AuditService.details("archivo", file.getOriginalFilename(),
                            "creados", result.getCreated(), "actualizados", result.getUpdated(),
                            "errores", result.getErrors()));
        }
        String message = dryRun
                ? "Vista previa: nada se ha guardado todavía"
                : String.format("Carga completada: %d creados, %d actualizados, %d con error",
                        result.getCreated(), result.getUpdated(), result.getErrors());
        return ResponseEntity.ok(ApiResponse.ok(result, message));
    }

    @GetMapping("/import/template")
    @Operation(summary = "Descargar plantilla Excel para la carga masiva (hoja Inventario + Instrucciones)")
    public ResponseEntity<byte[]> downloadImportTemplate() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=plantilla-inventario.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(inventoryTemplateService.buildTemplate());
    }

    @GetMapping("/export")
    @Operation(summary = "Exportar inventario completo a Excel")
    public ResponseEntity<byte[]> exportExcel() throws Exception {
        List<Product> products = productRepository.findAll(
                org.springframework.data.domain.Sort.by("id"));

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Inventario");
            sheet.createFreezePane(0, 1);

            // ── Estilos ──────────────────────────────────────────────────────
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setWrapText(true);
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerFont.setFontHeightInPoints((short) 10);
            headerStyle.setFont(headerFont);

            CellStyle dataStyle = wb.createCellStyle();
            dataStyle.setWrapText(true);
            dataStyle.setVerticalAlignment(VerticalAlignment.TOP);

            CellStyle numberStyle = wb.createCellStyle();
            DataFormat fmt = wb.createDataFormat();
            numberStyle.setDataFormat(fmt.getFormat("#,##0.00"));
            numberStyle.setVerticalAlignment(VerticalAlignment.TOP);

            CellStyle priceStyle = wb.createCellStyle();   // precios en soles enteros (V10)
            priceStyle.setDataFormat(fmt.getFormat("#,##0"));
            priceStyle.setVerticalAlignment(VerticalAlignment.TOP);

            // ── Cabecera ─────────────────────────────────────────────────────
            String[] cols = {"ID", "SKU", "NOMBRE", "CATEGORÍA", "DESCRIPCIÓN",
                             "UNIDAD", "STOCK", "STOCK MÍNIMO",
                             "PRECIO UNIT.", "MONEDA", "PROVEEDOR", "MARCA", "ACTIVO"};
            Row header = sheet.createRow(0);
            header.setHeightInPoints(28);
            for (int i = 0; i < cols.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(cols[i]);
                c.setCellStyle(headerStyle);
            }

            // ── Datos ────────────────────────────────────────────────────────
            int rowNum = 1;
            for (Product p : products) {
                Row row = sheet.createRow(rowNum++);
                row.setHeightInPoints(40);

                cell(row, 0, p.getId() != null ? p.getId().toString() : "", dataStyle);
                cell(row, 1, p.getSku() != null ? p.getSku() : "", dataStyle);
                cell(row, 2, p.getName() != null ? p.getName() : "", dataStyle);
                cell(row, 3, p.getCategory() != null ? p.getCategory() : "", dataStyle);
                cell(row, 4, p.getDescription() != null ? p.getDescription() : "", dataStyle);
                cell(row, 5, p.getUnit() != null ? p.getUnit() : "", dataStyle);

                Cell stockCell = row.createCell(6);
                stockCell.setCellValue(p.getStockQuantity() != null ? p.getStockQuantity().doubleValue() : 0);
                stockCell.setCellStyle(numberStyle);

                Cell minStockCell = row.createCell(7);
                minStockCell.setCellValue(p.getMinStockThreshold() != null ? p.getMinStockThreshold().doubleValue() : 0);
                minStockCell.setCellStyle(numberStyle);

                Cell priceCell = row.createCell(8);
                priceCell.setCellValue(p.getUnitPrice() != null ? p.getUnitPrice().doubleValue() : 0);
                priceCell.setCellStyle(priceStyle);

                cell(row, 9,  p.getCurrency() != null ? p.getCurrency() : "PEN", dataStyle);
                cell(row, 10, p.getSupplierName() != null ? p.getSupplierName() : "", dataStyle);
                cell(row, 11, p.getBrand() != null ? p.getBrand() : "", dataStyle);
                cell(row, 12, Boolean.TRUE.equals(p.getActive()) ? "Sí" : "No", dataStyle);
            }

            // ── Anchos de columna ────────────────────────────────────────────
            int[] widths = {8, 18, 35, 20, 60, 12, 12, 15, 15, 10, 25, 20, 10};
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

            wb.write(out);
            String filename = "inventario-" + LocalDate.now() + ".xlsx";
            auditService.record(AuditAction.INVENTORY_EXPORTED, "INVENTORY", null,
                    AuditService.details("archivo", filename, "productos", products.size()));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        }
    }

    /** Campos que cambiaron entre dos versiones del producto: {campo: {antes, despues}}. */
    private static Map<String, Object> productChanges(ProductResponse before, ProductResponse after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        diff(changes, "sku", before.getSku(), after.getSku());
        diff(changes, "nombre", before.getName(), after.getName());
        diff(changes, "categoria", before.getCategory(), after.getCategory());
        diff(changes, "marca", before.getBrand(), after.getBrand());
        diff(changes, "unidad", before.getUnit(), after.getUnit());
        diff(changes, "precio", before.getUnitPrice(), after.getUnitPrice());
        diff(changes, "moneda", before.getCurrency(), after.getCurrency());
        diff(changes, "stock", before.getStockQuantity(), after.getStockQuantity());
        diff(changes, "stockMinimo", before.getMinStockThreshold(), after.getMinStockThreshold());
        diff(changes, "proveedor", before.getSupplierName(), after.getSupplierName());
        diff(changes, "activo", before.getActive(), after.getActive());
        if (!Objects.equals(before.getDescription(), after.getDescription())) {
            changes.put("descripcion", "modificada");
        }
        return changes;
    }

    private static void diff(Map<String, Object> changes, String field, Object before, Object after) {
        boolean same = (before instanceof BigDecimal b && after instanceof BigDecimal a)
                ? b.compareTo(a) == 0
                : Objects.equals(before, after);
        if (!same) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("antes", before);
            change.put("despues", after);
            changes.put(field, change);
        }
    }

    private void cell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        c.setCellStyle(style);
    }
}
