package com.ansertech.controller;

import com.ansertech.domain.entity.Product;
import com.ansertech.dto.request.ProductRequest;
import com.ansertech.dto.request.StockUpdateRequest;
import com.ansertech.dto.response.ApiResponse;
import com.ansertech.dto.response.ProductResponse;
import com.ansertech.repository.ProductRepository;
import com.ansertech.service.inventory.InventoryService;
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

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
@Tag(name = "Inventario", description = "Gestión de productos e inventario")
public class InventoryController {

    private final InventoryService inventoryService;
    private final ProductRepository productRepository;

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
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(inventoryService.create(req), "Producto creado"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Actualizar producto")
    public ResponseEntity<ApiResponse<ProductResponse>> update(
            @PathVariable Long id, @Valid @RequestBody ProductRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(inventoryService.update(id, req)));
    }

    @PatchMapping("/{id}/stock")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Actualizar stock de un producto")
    public ResponseEntity<ApiResponse<ProductResponse>> updateStock(
            @PathVariable Long id, @Valid @RequestBody StockUpdateRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(inventoryService.updateStock(id, req), "Stock actualizado"));
    }

    @GetMapping("/low-stock")
    @Operation(summary = "Listar productos con stock bajo")
    public ResponseEntity<ApiResponse<List<ProductResponse>>> getLowStock() {
        return ResponseEntity.ok(ApiResponse.ok(inventoryService.getLowStock()));
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
                priceCell.setCellStyle(numberStyle);

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
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        }
    }

    private void cell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        c.setCellStyle(style);
    }
}
