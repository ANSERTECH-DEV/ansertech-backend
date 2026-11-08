package com.ansertech.service.inventory;

import com.ansertech.dto.response.ImportResultResponse;
import com.ansertech.dto.response.ImportResultResponse.Action;
import com.ansertech.repository.ProductRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * La plantilla descargable debe ser importable tal cual: si alguien la llena y la sube,
 * InventoryImportService tiene que reconocer todas sus columnas.
 */
@ExtendWith(MockitoExtension.class)
class InventoryTemplateServiceTest {

    @Mock private ProductRepository productRepository;
    private final InventoryTemplateService templateService = new InventoryTemplateService();

    @Test
    void template_hasDataSheetFirst_withAllColumns_andInstructionsSheet() throws Exception {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(templateService.buildTemplate()))) {
            assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo(InventoryTemplateService.DATA_SHEET);
            assertThat(wb.getSheet(InventoryTemplateService.HELP_SHEET)).isNotNull();

            Row header = wb.getSheetAt(0).getRow(0);
            assertThat(header.getLastCellNum()).isEqualTo((short) InventoryImportService.Col.values().length);
            // Sin filas de datos: los ejemplos viven solo en la hoja de instrucciones
            assertThat(wb.getSheetAt(0).getLastRowNum()).isZero();
        }
    }

    @Test
    void everyColumnLabel_isRecognizedByTheImporter() {
        for (InventoryImportService.Col col : InventoryImportService.Col.values()) {
            assertThat(col.headers).contains(InventoryImportService.normalize(col.label));
            assertThat(col.headers).contains(InventoryImportService.normalize(col.label + " *"));
        }
    }

    @Test
    void emptyTemplate_importsWithoutErrors() {
        when(productRepository.findAll()).thenReturn(new ArrayList<>());
        InventoryImportService importer = new InventoryImportService(productRepository);

        ImportResultResponse result = importer.importProducts(
                new ByteArrayInputStream(templateService.buildTemplate()), true);

        assertThat(result.getTotalRows()).isZero();
        assertThat(result.getErrors()).isZero();
    }

    @Test
    void filledTemplate_createsProduct() throws Exception {
        when(productRepository.findAll()).thenReturn(new ArrayList<>());
        InventoryImportService importer = new InventoryImportService(productRepository);

        byte[] filled;
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(templateService.buildTemplate()));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.getSheetAt(0);
            Row row = sheet.createRow(1);
            row.createCell(InventoryImportService.Col.SKU.ordinal()).setCellValue("PHM-90001");
            row.createCell(InventoryImportService.Col.NAME.ordinal()).setCellValue("Phmetro portátil");
            row.createCell(InventoryImportService.Col.PRICE.ordinal()).setCellValue(185.5);
            row.createCell(InventoryImportService.Col.ACTIVE.ordinal()).setCellValue("Sí");
            wb.write(out);
            filled = out.toByteArray();
        }

        ImportResultResponse result = importer.importProducts(new ByteArrayInputStream(filled), true);

        assertThat(result.getTotalRows()).isEqualTo(1);
        assertThat(result.getRows().get(0).getAction()).isEqualTo(Action.CREATE);
        // 185.5 se redondea a soles enteros (V10)
        assertThat(result.getRows().get(0).getNewPrice()).isEqualByComparingTo("186");
    }
}
