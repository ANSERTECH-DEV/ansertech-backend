package com.ansertech.service.inventory;

import com.ansertech.service.inventory.InventoryImportService.Col;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Plantilla descargable para la carga masiva de inventario.
 *
 * Hoja 1 "Inventario": solo encabezados (es la hoja que se importa). Los ejemplos van en la
 * hoja 2 "Instrucciones" a propósito: si estuvieran en la hoja 1, subir la plantilla sin
 * borrarlos crearía productos de ejemplo en el inventario real.
 *
 * Las columnas salen de InventoryImportService.Col, así plantilla e importación no se desalinean.
 */
@Service
public class InventoryTemplateService {

    static final String DATA_SHEET = "Inventario";
    static final String HELP_SHEET = "Instrucciones";
    private static final Set<Col> REQUIRED_FOR_NEW = EnumSet.of(Col.SKU, Col.NAME, Col.PRICE);
    private static final Set<Col> NUMERIC = EnumSet.of(Col.STOCK, Col.MIN_STOCK, Col.PRICE);

    public byte[] buildTemplate() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            buildDataSheet(wb, st);
            buildHelpSheet(wb, st);
            wb.setActiveSheet(0);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── Hoja 1: Inventario (la que se importa) ───────────────────────────────

    private void buildDataSheet(Workbook wb, Styles st) {
        Sheet sheet = wb.createSheet(DATA_SHEET);
        sheet.createFreezePane(0, 1);
        Row header = sheet.createRow(0);
        header.setHeightInPoints(30);

        Col[] cols = Col.values();
        for (int i = 0; i < cols.length; i++) {
            Cell c = header.createCell(i);
            c.setCellValue(REQUIRED_FOR_NEW.contains(cols[i]) ? cols[i].label + " *" : cols[i].label);
            c.setCellStyle(REQUIRED_FOR_NEW.contains(cols[i]) ? st.headerRequired : st.header);
            sheet.setColumnWidth(i, width(cols[i]) * 256);
            if (cols[i] == Col.PRICE) sheet.setDefaultColumnStyle(i, st.money);
        }

        int lastRow = InventoryImportService.MAX_ROWS;
        DataValidationHelper dv = sheet.getDataValidationHelper();
        for (int i = 0; i < cols.length; i++) {
            CellRangeAddressList range = new CellRangeAddressList(1, lastRow, i, i);
            DataValidation validation = null;
            if (cols[i] == Col.ACTIVE) {
                validation = dv.createValidation(dv.createExplicitListConstraint(new String[]{"Sí", "No"}), range);
                validation.createErrorBox("ACTIVO", "Escribe Sí o No.");
            } else if (NUMERIC.contains(cols[i])) {
                validation = dv.createValidation(
                        dv.createDecimalConstraint(DataValidationConstraint.OperatorType.GREATER_OR_EQUAL, "0", null), range);
                validation.setErrorStyle(DataValidation.ErrorStyle.WARNING);   // aviso, no bloquea
                validation.createErrorBox(cols[i].label, "Debe ser un número mayor o igual a 0.");
            }
            if (validation != null) {
                validation.setShowErrorBox(true);
                sheet.addValidationData(validation);
            }
        }
    }

    // ── Hoja 2: Instrucciones y ejemplos ─────────────────────────────────────

    private void buildHelpSheet(Workbook wb, Styles st) {
        Sheet sheet = wb.createSheet(HELP_SHEET);
        sheet.setColumnWidth(0, 22 * 256);
        sheet.setColumnWidth(1, 24 * 256);
        sheet.setColumnWidth(2, 70 * 256);
        sheet.setColumnWidth(3, 30 * 256);
        int r = 0;

        r = title(sheet, st, r, "Plantilla de carga masiva de inventario — Ansertech");
        for (String rule : List.of(
                "1. Llena la hoja \"Inventario\" desde la fila 2. No cambies los encabezados de la fila 1.",
                "2. Columnas marcadas con * : SKU siempre; NOMBRE y PRECIO UNIT. solo para productos nuevos.",
                "3. El sistema busca cada fila por SKU: si no existe crea el producto; si existe lo actualiza.",
                "4. Al actualizar, una celda vacía NO borra nada: el valor actual se mantiene.",
                "5. Precios SIN IGV. Números: 1450  ·  1450,50  ·  1.450,50  (sin valores negativos).",
                "6. Sube el archivo en Inventario → Carga masiva. Verás una vista previa antes de guardar;",
                "    las filas con error se omiten y el resto se guarda al pulsar \"Finalizar\".",
                "7. También puedes usar el Excel de Dashboard → \"Exportar inventario\": editarlo y subirlo de nuevo.")) {
            text(sheet, st.body, r++, rule);
        }
        r++;

        r = title(sheet, st, r, "Columnas");
        r = tableRow(sheet, st.header, r, "Columna", "¿Obligatoria?", "Qué poner", "Si se deja vacía");
        for (Col col : Col.values()) {
            r = tableRow(sheet, st.wrap, r, col.label, col.required, col.description, col.ifBlank);
        }
        r++;

        r = title(sheet, st, r, "Ejemplos (NO se importan desde esta hoja)");
        text(sheet, st.body, r++, "A) Producto nuevo:");
        r = example(sheet, st, r,
                new String[]{"SKU", "NOMBRE", "CATEGORÍA", "STOCK", "PRECIO UNIT.", "MARCA"},
                new String[]{"PHM-90001", "Phmetro portátil HI98107", "Phmetros", "40", "186", "Hanna"});
        text(sheet, st.body, r++, "B) Solo cambiar precios (lo demás vacío):");
        r = example(sheet, st, r, new String[]{"SKU", "PRECIO UNIT."}, new String[]{"BAL-35422", "1450"});
        text(sheet, st.body, r++, "C) Solo cambiar stock (p. ej. al recibir mercadería):");
        example(sheet, st, r, new String[]{"SKU", "STOCK"}, new String[]{"BAL-35422", "180"});
    }

    private int title(Sheet sheet, Styles st, int r, String text) {
        Row row = sheet.createRow(r);
        Cell c = row.createCell(0);
        c.setCellValue(text);
        c.setCellStyle(st.title);
        sheet.addMergedRegion(new CellRangeAddress(r, r, 0, 3));
        return r + 1;
    }

    private void text(Sheet sheet, CellStyle style, int r, String text) {
        Cell c = sheet.createRow(r).createCell(0);
        c.setCellValue(text);
        c.setCellStyle(style);
    }

    private int tableRow(Sheet sheet, CellStyle style, int r, String... values) {
        Row row = sheet.createRow(r);
        for (int i = 0; i < values.length; i++) {
            Cell c = row.createCell(i);
            c.setCellValue(values[i]);
            c.setCellStyle(style);
        }
        return r + 1;
    }

    private int example(Sheet sheet, Styles st, int r, String[] headers, String[] values) {
        r = tableRow(sheet, st.exampleHeader, r, headers);
        r = tableRow(sheet, st.wrap, r, values);
        return r + 1;
    }

    private static int width(Col col) {
        return switch (col) {
            case NAME -> 40;
            case DESCRIPTION -> 60;
            case SKU, CATEGORY, SUPPLIER, BRAND -> 20;
            case PRICE, MIN_STOCK -> 15;
            default -> 12;
        };
    }

    private static final class Styles {
        final CellStyle header, headerRequired, exampleHeader, title, body, wrap, money;

        Styles(Workbook wb) {
            header = headerStyle(wb, IndexedColors.DARK_BLUE);
            headerRequired = headerStyle(wb, IndexedColors.DARK_RED);
            exampleHeader = headerStyle(wb, IndexedColors.GREY_50_PERCENT);

            title = wb.createCellStyle();
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 12);
            title.setFont(titleFont);

            body = wb.createCellStyle();

            wrap = wb.createCellStyle();
            wrap.setWrapText(true);
            wrap.setVerticalAlignment(VerticalAlignment.TOP);
            wrap.setBorderBottom(BorderStyle.THIN);

            money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0"));   // soles enteros (V10)
        }

        private static CellStyle headerStyle(Workbook wb, IndexedColors color) {
            CellStyle s = wb.createCellStyle();
            s.setFillForegroundColor(color.getIndex());
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            s.setAlignment(HorizontalAlignment.CENTER);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            s.setWrapText(true);
            Font f = wb.createFont();
            f.setBold(true);
            f.setColor(IndexedColors.WHITE.getIndex());
            f.setFontHeightInPoints((short) 10);
            s.setFont(f);
            return s;
        }
    }
}
