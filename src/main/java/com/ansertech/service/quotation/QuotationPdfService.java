package com.ansertech.service.quotation;

import com.ansertech.domain.entity.Quotation;
import com.ansertech.domain.entity.QuotationItem;
import com.ansertech.domain.entity.Rfq;
import com.ansertech.domain.enums.QuotationStatus;
import com.ansertech.repository.QuotationRepository;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuotationPdfService {

    private static final DeviceRgb NAVY = new DeviceRgb(26, 42, 74);
    private static final DeviceRgb BLUE = new DeviceRgb(58, 143, 209);
    private static final DeviceRgb LIGHT_GRAY = new DeviceRgb(244, 246, 250);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final QuotationRepository quotationRepository;

    @Value("${storage.base-path:./storage}")
    private String storagePath;

    @Transactional
    public String generateAndStore(Quotation quotation) {
        try {
            Path dir = Paths.get(storagePath, "quotations",
                    String.valueOf(quotation.getCreatedAt().getYear()),
                    String.format("%02d", quotation.getCreatedAt().getMonthValue()));
            Files.createDirectories(dir);

            Path filePath = dir.resolve(quotation.getQuotationNumber() + ".pdf");
            buildPdf(quotation, filePath.toString());

            quotation.setPdfPath(filePath.toString());
            quotationRepository.save(quotation);
            log.info("PDF generado: {}", filePath);
            return filePath.toString();
        } catch (Exception e) {
            log.error("Error generando PDF para {}: {}", quotation.getQuotationNumber(), e.getMessage());
            throw new RuntimeException("Error generando PDF", e);
        }
    }

    private void buildPdf(Quotation quotation, String filePath) throws IOException {
        try (PdfDocument pdf = new PdfDocument(new PdfWriter(filePath));
             Document doc = new Document(pdf)) {

            PdfFont bold = PdfFontFactory.createFont("Helvetica-Bold");
            PdfFont regular = PdfFontFactory.createFont("Helvetica");

            boolean isDraft = quotation.getStatus() == QuotationStatus.DRAFT;

            // === MARCA DE AGUA BORRADOR ===
            if (isDraft) {
                Paragraph watermark = new Paragraph("BORRADOR")
                        .setFont(bold).setFontSize(72)
                        .setFontColor(new DeviceRgb(200, 200, 200))
                        .setFixedPosition(100, 300, 500);
                doc.add(watermark);
            }

            // === HEADER ===
            Table header = new Table(UnitValue.createPercentArray(new float[]{50, 50})).useAllAvailableWidth();
            Cell companyCell = new Cell()
                    .add(new Paragraph("ANSERTECH PERÚ S.A.C.").setFont(bold).setFontSize(16).setFontColor(NAVY))
                    .add(new Paragraph("RUC: 20600293321").setFont(regular).setFontSize(9))
                    .add(new Paragraph("Lima, Perú — www.ansertech.pe").setFont(regular).setFontSize(9))
                    .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER);
            header.addCell(companyCell);

            Cell titleCell = new Cell()
                    .add(new Paragraph("COTIZACIÓN").setFont(bold).setFontSize(20)
                            .setTextAlignment(TextAlignment.RIGHT).setFontColor(BLUE))
                    .add(new Paragraph(quotation.getQuotationNumber())
                            .setFont(bold).setFontSize(12).setTextAlignment(TextAlignment.RIGHT).setFontColor(NAVY))
                    .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER);
            header.addCell(titleCell);
            doc.add(header);
            doc.add(new LineSeparator(new com.itextpdf.kernel.pdf.canvas.draw.SolidLine(2f)));
            doc.add(new Paragraph("\n").setFontSize(4));

            // === DATOS DEL CLIENTE ===
            Rfq rfq = quotation.getRfq();
            if (rfq != null) {
                Table clientTable = new Table(UnitValue.createPercentArray(new float[]{30, 70})).useAllAvailableWidth();
                addInfoRow(clientTable, "Cliente:", rfq.getClientName(), bold, regular);
                addInfoRow(clientTable, "Empresa:", rfq.getClientCompany(), bold, regular);
                addInfoRow(clientTable, "Correo:", rfq.getClientEmail(), bold, regular);
                addInfoRow(clientTable, "Válido hasta:",
                        quotation.getValidUntil() != null ? quotation.getValidUntil().format(DATE_FMT) : "15 días",
                        bold, regular);
                doc.add(clientTable);
            }
            doc.add(new Paragraph("\n").setFontSize(6));

            // === TABLA DE ÍTEMS ===
            Table itemTable = new Table(UnitValue.createPercentArray(new float[]{40, 12, 12, 18, 18}))
                    .useAllAvailableWidth();
            addTableHeader(itemTable, bold, "Descripción", "Cantidad", "Unidad", "P. Unit.", "Subtotal");

            boolean alternate = false;
            for (QuotationItem item : quotation.getItems()) {
                DeviceRgb rowColor = alternate ? LIGHT_GRAY : new DeviceRgb(255, 255, 255);
                addTableRow(itemTable, regular, rowColor,
                        item.getDescription(),
                        formatNum(item.getQuantity()),
                        item.getUnit(),
                        formatMoney(item.getUnitPrice()),
                        formatMoney(item.getSubtotal()));
                alternate = !alternate;
            }
            doc.add(itemTable);

            // === TOTALES ===
            doc.add(new Paragraph("\n").setFontSize(4));
            Table totals = new Table(UnitValue.createPercentArray(new float[]{70, 15, 15})).useAllAvailableWidth();
            addTotalRow(totals, regular, bold, "Subtotal:", quotation.getSubtotal(), false);
            addTotalRow(totals, regular, bold, "IGV (18%):", quotation.getIgv(), false);
            addTotalRow(totals, regular, bold, "TOTAL " + quotation.getCurrency() + ":", quotation.getTotal(), true);
            doc.add(totals);

            // === AI SUMMARY ===
            if (quotation.getAiSummary() != null && !quotation.getAiSummary().isBlank()) {
                doc.add(new Paragraph("\n").setFontSize(6));
                doc.add(new Paragraph("Análisis AI:").setFont(bold).setFontSize(9).setFontColor(NAVY));
                doc.add(new Paragraph(quotation.getAiSummary()).setFont(regular).setFontSize(8)
                        .setBackgroundColor(LIGHT_GRAY).setPadding(6));
            }

            // === FOOTER ===
            doc.add(new Paragraph("\n\n").setFontSize(4));
            doc.add(new LineSeparator(new com.itextpdf.kernel.pdf.canvas.draw.SolidLine(1f)));
            doc.add(new Paragraph("Esta cotización es válida por 15 días desde su emisión. " +
                    "Precios incluyen IGV. Sujeto a disponibilidad de stock.")
                    .setFont(regular).setFontSize(8).setFontColor(ColorConstants.GRAY)
                    .setTextAlignment(TextAlignment.CENTER));
        }
    }

    private void addInfoRow(Table t, String label, String value, PdfFont bold, PdfFont regular) {
        t.addCell(new Cell().add(new Paragraph(label).setFont(bold).setFontSize(9))
                .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER));
        t.addCell(new Cell().add(new Paragraph(value != null ? value : "—").setFont(regular).setFontSize(9))
                .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER));
    }

    private void addTableHeader(Table t, PdfFont bold, String... headers) {
        for (String h : headers) {
            t.addHeaderCell(new Cell()
                    .add(new Paragraph(h).setFont(bold).setFontSize(9).setFontColor(ColorConstants.WHITE))
                    .setBackgroundColor(NAVY));
        }
    }

    private void addTableRow(Table t, PdfFont regular, DeviceRgb bg, String... values) {
        for (String v : values) {
            t.addCell(new Cell()
                    .add(new Paragraph(v != null ? v : "").setFont(regular).setFontSize(9))
                    .setBackgroundColor(bg));
        }
    }

    private void addTotalRow(Table t, PdfFont regular, PdfFont bold,
                              String label, BigDecimal amount, boolean highlight) {
        t.addCell(new Cell().setBorder(com.itextpdf.layout.borders.Border.NO_BORDER)
                .add(new Paragraph("")));
        t.addCell(new Cell().add(new Paragraph(label)
                .setFont(highlight ? bold : regular).setFontSize(10)
                .setTextAlignment(TextAlignment.RIGHT))
                .setBackgroundColor(highlight ? NAVY : LIGHT_GRAY)
                .setFontColor(highlight ? ColorConstants.WHITE : ColorConstants.BLACK));
        t.addCell(new Cell().add(new Paragraph(formatMoney(amount))
                .setFont(bold).setFontSize(10).setTextAlignment(TextAlignment.RIGHT))
                .setBackgroundColor(highlight ? BLUE : LIGHT_GRAY)
                .setFontColor(highlight ? ColorConstants.WHITE : ColorConstants.BLACK));
    }

    private String formatMoney(BigDecimal value) {
        if (value == null) return "0.00";
        return String.format("%,.2f", value);
    }

    private String formatNum(BigDecimal value) {
        if (value == null) return "0";
        return value.stripTrailingZeros().toPlainString();
    }
}
