package com.ansertech.service.quotation;

import com.ansertech.domain.entity.Quotation;
import com.ansertech.domain.entity.QuotationItem;
import com.ansertech.domain.entity.Rfq;
import com.ansertech.domain.enums.AvailabilityStatus;
import com.ansertech.repository.QuotationRepository;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuotationPdfService {

    // ── Colors ───────────────────────────────────────────────────────────────
    private static final DeviceRgb NAVY       = new DeviceRgb(0, 51, 102);
    private static final DeviceRgb NAVY_LIGHT = new DeviceRgb(166, 194, 222);
    private static final DeviceRgb LIGHT_BLUE = new DeviceRgb(235, 243, 250);
    private static final DeviceRgb ROW_SEP    = new DeviceRgb(220, 220, 220);

    // ── Company static data ──────────────────────────────────────────────────
    private static final String CO_NAME    = "ANSERTECH PERU S.A.C.";
    private static final String CO_RUC     = "20600293321";
    private static final String CO_ADDRESS = "Av. Los Dominicos N° 691 Urb. Jorge Chávez - Callao";
    private static final String CO_PHONE   = "5756672 - RPM: #996908111 - RPC: 953299635";
    private static final String CO_EMAIL   = "ansertechperu@gmail.com";
    private static final String CO_WEB     = "www.ansertechperu.com";
    private static final String CO_FIRMA   = "EDUARDO LUQUE";
    private static final String CO_DEPT    = "DEPARTAMENTO DE SERVICIO";

    // ── Bank accounts ─────────────────────────────────────────────────────────
    private static final String[][] BANKS = {
        {"BANCO SCOTIABANK",               "DOLARES", "000-4591082",      "009-229-000004591082-65"},
        {"BANCO DE CREDITO (BCP)",         "DOLARES", "191-2444566-1-94", "00219100244456619455"},
        {"BANCO DE CREDITO (BCP)",         "SOLES",   "191-2469366-0-89", "00219100246936608956"},
        {"BANCO DE LA NACION (DETRACCION)","SOLES",   "00-028-030797",    "01802800002803079716"}
    };

    // ── Azure ─────────────────────────────────────────────────────────────────
    private final QuotationRepository quotationRepository;

    @Value("${azure.storage.connection-string:}")
    private String connectionString;

    @Value("${azure.storage.container-name:quotations}")
    private String containerName;

    // ── Public API ────────────────────────────────────────────────────────────

    public byte[] downloadPdf(String blobUrl) {
        try {
            String blobName = blobUrl.substring(
                blobUrl.lastIndexOf(containerName + "/") + containerName.length() + 1);
            return buildClient()
                    .getBlobContainerClient(containerName)
                    .getBlobClient(blobName)
                    .downloadContent()
                    .toBytes();
        } catch (Exception e) {
            log.error("Error descargando PDF desde Azure Blob: {}", e.getMessage());
            throw new RuntimeException("No se pudo descargar el PDF", e);
        }
    }

    @Transactional
    public String generateAndStore(Quotation quotation) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            buildPdf(quotation, baos);
            byte[] pdfBytes = baos.toByteArray();

            BlobContainerClient containerClient = buildClient().getBlobContainerClient(containerName);
            if (!containerClient.exists()) containerClient.create();

            String blobName = String.format("%d/%02d/%s.pdf",
                    quotation.getCreatedAt().getYear(),
                    quotation.getCreatedAt().getMonthValue(),
                    quotation.getQuotationNumber());

            BlobClient blobClient = containerClient.getBlobClient(blobName);
            blobClient.upload(new ByteArrayInputStream(pdfBytes), pdfBytes.length, true);

            BlobHttpHeaders headers = new BlobHttpHeaders().setContentType("application/pdf");
            blobClient.setHttpHeaders(headers);

            String pdfUrl = blobClient.getBlobUrl();
            quotation.setPdfPath(pdfUrl);
            quotationRepository.save(quotation);

            log.info("PDF generado y subido a Azure: {}", pdfUrl);
            return pdfUrl;
        } catch (Exception e) {
            log.error("Error generando/subiendo PDF para {}: {}", quotation.getQuotationNumber(), e.getMessage());
            throw new RuntimeException("Error generando PDF", e);
        }
    }

    // ── PDF build ─────────────────────────────────────────────────────────────

    private void buildPdf(Quotation quotation, ByteArrayOutputStream baos) throws IOException {
        try (PdfDocument pdf = new PdfDocument(new PdfWriter(baos));
             Document doc = new Document(pdf)) {

            doc.setMargins(28, 36, 28, 36);

            PdfFont regular    = PdfFontFactory.createFont(StandardFonts.HELVETICA);
            PdfFont bold       = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
            PdfFont italic     = PdfFontFactory.createFont(StandardFonts.HELVETICA_OBLIQUE);
            PdfFont boldItalic = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLDOBLIQUE);

            // ── 1. HEADER ─────────────────────────────────────────────────
            Table header = new Table(UnitValue.createPercentArray(new float[]{38, 62}))
                    .useAllAvailableWidth().setBorder(Border.NO_BORDER);

            Cell logoCell = new Cell().setBorder(Border.NO_BORDER)
                    .setVerticalAlignment(VerticalAlignment.MIDDLE);
            try {
                byte[] logoBytes = getClass().getClassLoader()
                        .getResourceAsStream("logo.png").readAllBytes();
                Image logo = new Image(ImageDataFactory.create(logoBytes));
                logo.setWidth(UnitValue.createPercentValue(85));
                logoCell.add(logo);
            } catch (Exception e) {
                logoCell.add(new Paragraph(CO_NAME).setFont(bold).setFontSize(13).setFontColor(NAVY));
            }
            header.addCell(logoCell);

            Cell contactCell = new Cell().setBorder(Border.NO_BORDER)
                    .setVerticalAlignment(VerticalAlignment.MIDDLE);
            contactCell.add(line("Dirección: " + CO_ADDRESS, regular, 8, TextAlignment.RIGHT));
            contactCell.add(line("Teléfono Fijo: " + CO_PHONE, regular, 8, TextAlignment.RIGHT));
            contactCell.add(line("E-mail: " + CO_EMAIL, regular, 8, TextAlignment.RIGHT));
            contactCell.add(line("Web: " + CO_WEB, regular, 8, TextAlignment.RIGHT));
            header.addCell(contactCell);
            doc.add(header);

            // ── 2. TITLE BAR ──────────────────────────────────────────────
            doc.add(new Table(UnitValue.createPercentArray(new float[]{100}))
                    .useAllAvailableWidth()
                    .addCell(new Cell()
                            .setBackgroundColor(NAVY).setBorder(Border.NO_BORDER)
                            .setPaddingTop(5).setPaddingBottom(5)
                            .add(new Paragraph("COTIZACION " + quotation.getQuotationNumber())
                                    .setFont(boldItalic).setFontSize(11)
                                    .setFontColor(ColorConstants.WHITE)
                                    .setTextAlignment(TextAlignment.CENTER))));

            doc.add(gap(3));

            // ── 3. CLIENT / BILLING DATA ──────────────────────────────────
            Rfq rfq = quotation.getRfq();
            String currency  = quotation.getCurrency() != null ? quotation.getCurrency() : "PEN";
            String moneda    = "PEN".equals(currency) ? "SOLES" : "DOLARES";
            String fechaEmis = quotation.getCreatedAt() != null
                    ? quotation.getCreatedAt()
                        .format(java.time.format.DateTimeFormatter.ofPattern(
                            "EEEE, d 'de' MMMM 'del' yyyy", Locale.forLanguageTag("es-PE")))
                        .toUpperCase()
                    : "";
            String referencia = "";
            if (rfq != null && rfq.getEmail() != null && rfq.getEmail().getSubject() != null) {
                referencia = rfq.getEmail().getSubject().toUpperCase();
                if (referencia.length() > 55) referencia = referencia.substring(0, 52) + "...";
            }

            SolidBorder cellBorder = new SolidBorder(NAVY, 0.5f);

            Table clientBilling = new Table(UnitValue.createPercentArray(new float[]{50, 50}))
                    .useAllAvailableWidth()
                    .setBorder(cellBorder);

            // Section headers
            clientBilling.addCell(sectionHeader("DATOS DE CLIENTE:", bold));
            clientBilling.addCell(sectionHeader("DATOS PARA ORDEN DE COMPRA Y/O FACTURACION:", bold));

            // Left data
            Table leftData = new Table(UnitValue.createPercentArray(new float[]{38, 62}))
                    .useAllAvailableWidth().setBorder(Border.NO_BORDER);
            dataRow(leftData, "RAZON SOCIAL:", safe(rfq, Rfq::getClientCompany), boldItalic, italic);
            dataRow(leftData, "RUC:", "-", boldItalic, italic);
            dataRow(leftData, "DIRECCION:", "-", boldItalic, italic);
            dataRow(leftData, "ATENCION:", safe(rfq, Rfq::getClientName), boldItalic, italic);
            dataRow(leftData, "CARGO/AREA:", "-", boldItalic, italic);
            dataRow(leftData, "CORREO:", safe(rfq, Rfq::getClientEmail), boldItalic, italic);
            dataRow(leftData, "TELEFONO:", "-", boldItalic, italic);
            dataRow(leftData, "CELULAR:", safe(rfq, Rfq::getClientPhone), boldItalic, italic);
            dataRow(leftData, "FECHA DE EMISION:", fechaEmis, boldItalic, italic);
            clientBilling.addCell(new Cell().setBorder(cellBorder).setPadding(0).add(leftData));

            // Right data (two sections)
            Table rightTop = new Table(UnitValue.createPercentArray(new float[]{40, 60}))
                    .useAllAvailableWidth().setBorder(Border.NO_BORDER);
            dataRow(rightTop, "RAZON SOCIAL:", CO_NAME, boldItalic, italic);
            dataRow(rightTop, "RUC:", CO_RUC, boldItalic, italic);
            dataRow(rightTop, "DIRECCION:", CO_ADDRESS, boldItalic, italic);

            Table rightBottom = new Table(UnitValue.createPercentArray(new float[]{40, 60}))
                    .useAllAvailableWidth().setBorder(Border.NO_BORDER);
            rightBottom.addCell(new Cell(1, 2)
                    .setBackgroundColor(NAVY_LIGHT).setBorder(Border.NO_BORDER).setPadding(2)
                    .add(new Paragraph("CONDICIONES DE PAGO:")
                            .setFont(bold).setFontSize(7).setFontColor(NAVY)));
            dataRow(rightBottom, "FORMA DE PAGO:", "30 DIAS POSTERIOR A LA FACTURACION", boldItalic, italic);
            dataRow(rightBottom, "MONEDA:", moneda, boldItalic, italic);
            dataRow(rightBottom, "PRECIOS:", "PRECIOS UNITARIOS NO INCLUYEN IGV", boldItalic, italic);
            dataRow(rightBottom, "VIGENCIA DE COT.:", "15 DIAS", boldItalic, italic);
            dataRow(rightBottom, "REFERENCIA:", referencia, boldItalic, italic);

            Cell rightCell = new Cell().setBorder(cellBorder).setPadding(0);
            rightCell.add(rightTop);
            rightCell.add(rightBottom);
            clientBilling.addCell(rightCell);
            doc.add(clientBilling);

            doc.add(gap(4));

            // ── 4. ITEMS TABLE ────────────────────────────────────────────
            Table items = new Table(UnitValue.createPercentArray(new float[]{4, 11, 46, 9, 6, 12, 12}))
                    .useAllAvailableWidth();

            String[] cols = {"ITEM", "CODIGO", "DESCRIPCIÓN DE PRODUCTO / SERVICIO",
                             "ESTADO", "CANT.", "PRECIO. UN", "TOTAL"};
            for (int i = 0; i < cols.length; i++) {
                TextAlignment ta = (i == 2) ? TextAlignment.LEFT : TextAlignment.CENTER;
                items.addHeaderCell(new Cell()
                        .setBackgroundColor(NAVY).setBorder(new SolidBorder(ColorConstants.WHITE, 0.5f))
                        .setPadding(4)
                        .add(new Paragraph(cols[i]).setFont(boldItalic).setFontSize(8)
                                .setFontColor(ColorConstants.WHITE).setTextAlignment(ta)));
            }

            // Optional group label from email subject
            String groupLabel = "";
            if (rfq != null && rfq.getEmail() != null && rfq.getEmail().getSubject() != null) {
                groupLabel = rfq.getEmail().getSubject().toUpperCase();
                if (groupLabel.length() > 80) groupLabel = groupLabel.substring(0, 77) + "...";
            }
            if (!groupLabel.isBlank()) {
                items.addCell(new Cell(1, 7).setBorder(Border.NO_BORDER)
                        .setPaddingTop(6).setPaddingBottom(2)
                        .add(new Paragraph(groupLabel).setFont(boldItalic).setFontSize(9)
                                .setFontColor(NAVY).setTextAlignment(TextAlignment.CENTER)));
            }

            int num = 1;
            for (QuotationItem item : quotation.getItems()) {
                String sku = (item.getProduct() != null && item.getProduct().getSku() != null)
                        ? item.getProduct().getSku() : "-";
                addItemRow(items, num++, sku, item, currency, italic, boldItalic);
            }

            doc.add(items);

            // ── 5. TOTALS ─────────────────────────────────────────────────
            Table totals = new Table(UnitValue.createPercentArray(new float[]{52, 24, 24}))
                    .useAllAvailableWidth();

            totalRow(totals, "SUB TOTAL:", currency + "  " + money(quotation.getSubtotal()),
                     false, italic, boldItalic);
            totalRow(totals, "IGV 18%:",   currency + "  " + money(quotation.getIgv()),
                     false, italic, boldItalic);
            totalRow(totals, "TOTAL",      currency + "  " + money(quotation.getTotal()),
                     true, italic, boldItalic);

            doc.add(totals);
            doc.add(gap(8));

            // ── 6. CONDICIONES GENERALES ──────────────────────────────────
            Table cond = new Table(UnitValue.createPercentArray(new float[]{100}))
                    .useAllAvailableWidth();
            cond.addCell(new Cell().setBackgroundColor(NAVY).setBorder(Border.NO_BORDER)
                    .setPadding(4)
                    .add(new Paragraph("CONDICIONES GENERALES")
                            .setFont(boldItalic).setFontSize(9).setFontColor(ColorConstants.WHITE)
                            .setTextAlignment(TextAlignment.CENTER)));
            cond.addCell(condRow("ENTREGA DE LOS BIENES:",
                    "INMEDIATA POSTERIOR A SU ORDEN DE COMPRA", boldItalic, italic));
            cond.addCell(condRow("FORMA DE PAGO:",
                    "30 DIAS POSTERIOR A LA FACTURACION", boldItalic, italic));
            cond.addCell(condRow("VALIDEZ DE LA OFERTA:", "15 DIAS", boldItalic, italic));
            doc.add(cond);

            doc.add(new Paragraph("SIRVASE DEPOSITAR EN NUESTRAS CUENTAS:")
                    .setFont(italic).setFontSize(8).setMarginTop(6).setMarginBottom(1));
            doc.add(new Paragraph(CO_NAME + ":").setFont(italic).setFontSize(8).setMarginBottom(3));

            // Bank table
            Table banks = new Table(UnitValue.createPercentArray(new float[]{32, 14, 26, 28}))
                    .useAllAvailableWidth();
            for (String h : new String[]{"BANCO", "MONEDA", "NRO DE CUENTA", "CCI"}) {
                banks.addHeaderCell(new Cell()
                        .setBackgroundColor(NAVY_LIGHT).setBorder(new SolidBorder(NAVY, 0.5f))
                        .setPadding(3)
                        .add(new Paragraph(h).setFont(boldItalic).setFontSize(8)
                                .setTextAlignment(TextAlignment.CENTER)));
            }
            TextAlignment[] bankAligns = {TextAlignment.LEFT, TextAlignment.CENTER,
                                          TextAlignment.CENTER, TextAlignment.RIGHT};
            for (String[] bank : BANKS) {
                for (int i = 0; i < 4; i++) {
                    banks.addCell(new Cell().setBorder(new SolidBorder(NAVY_LIGHT, 0.5f))
                            .setPadding(3)
                            .add(new Paragraph(bank[i]).setFont(italic).setFontSize(7.5f)
                                    .setTextAlignment(bankAligns[i])));
                }
            }
            doc.add(banks);
            doc.add(gap(8));

            // ── 7. SIGNATURE ──────────────────────────────────────────────
            doc.add(new Paragraph("ATTE.").setFont(italic).setFontSize(9).setMarginBottom(0));
            doc.add(gap(16));
            doc.add(new Paragraph(CO_FIRMA).setFont(italic).setFontSize(9).setMarginBottom(0));
            doc.add(new Paragraph(CO_DEPT).setFont(italic).setFontSize(9).setMarginBottom(0));
            doc.add(new Paragraph(CO_NAME).setFont(italic).setFontSize(9));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Paragraph line(String text, PdfFont font, float size, TextAlignment align) {
        return new Paragraph(text).setFont(font).setFontSize(size)
                .setTextAlignment(align).setMarginBottom(1);
    }

    private Paragraph gap(float height) {
        return new Paragraph("").setMarginBottom(height);
    }

    private Cell sectionHeader(String text, PdfFont bold) {
        return new Cell()
                .setBackgroundColor(NAVY_LIGHT).setBorder(new SolidBorder(NAVY, 0.5f))
                .setPadding(3)
                .add(new Paragraph(text).setFont(bold).setFontSize(8).setFontColor(NAVY));
    }

    private void dataRow(Table t, String label, String value, PdfFont lf, PdfFont vf) {
        t.addCell(new Cell().setBorder(Border.NO_BORDER).setPadding(2)
                .add(new Paragraph(label).setFont(lf).setFontSize(7).setFontColor(NAVY)));
        t.addCell(new Cell().setBorder(Border.NO_BORDER).setPadding(2)
                .add(new Paragraph(value).setFont(vf).setFontSize(7)));
    }

    private void addItemRow(Table t, int num, String sku, QuotationItem item,
                             String currency, PdfFont italic, PdfFont boldItalic) {
        String estado = switch (item.getAvailabilityStatus() != null
                ? item.getAvailabilityStatus() : AvailabilityStatus.IN_STOCK) {
            case IN_STOCK    -> "STOCK";
            case OUT_OF_STOCK-> "SIN STOCK";
            case ON_ORDER    -> "PEDIDO";
        };
        t.addCell(iCell(String.valueOf(num), italic, TextAlignment.CENTER));
        t.addCell(iCell(sku, italic, TextAlignment.CENTER));
        t.addCell(iCell(item.getDescription(), italic, TextAlignment.LEFT));
        t.addCell(iCell(estado, boldItalic, TextAlignment.CENTER));
        t.addCell(iCell(num(item.getQuantity()), italic, TextAlignment.CENTER));
        t.addCell(iCell(currency + " " + money(item.getUnitPrice()), italic, TextAlignment.RIGHT));
        t.addCell(iCell(currency + " " + money(item.getSubtotal()), italic, TextAlignment.RIGHT));
    }

    private Cell iCell(String val, PdfFont font, TextAlignment align) {
        return new Cell()
                .setBorder(Border.NO_BORDER)
                .setBorderBottom(new SolidBorder(ROW_SEP, 0.3f))
                .setPaddingTop(8).setPaddingBottom(8).setPaddingLeft(4).setPaddingRight(4)
                .add(new Paragraph(val != null ? val : "").setFont(font)
                        .setFontSize(8).setTextAlignment(align));
    }

    private void totalRow(Table t, String label, String value,
                           boolean highlight, PdfFont italic, PdfFont boldItalic) {
        t.addCell(new Cell().setBorder(Border.NO_BORDER).add(new Paragraph("")));
        t.addCell(new Cell()
                .setBackgroundColor(highlight ? NAVY : null)
                .setBorder(new SolidBorder(NAVY_LIGHT, 0.5f)).setPadding(4)
                .add(new Paragraph(label)
                        .setFont(highlight ? boldItalic : italic).setFontSize(9)
                        .setFontColor(highlight ? ColorConstants.WHITE : ColorConstants.BLACK)
                        .setTextAlignment(TextAlignment.RIGHT)));
        t.addCell(new Cell()
                .setBackgroundColor(highlight ? NAVY_LIGHT : null)
                .setBorder(new SolidBorder(NAVY_LIGHT, 0.5f)).setPadding(4)
                .add(new Paragraph(value)
                        .setFont(highlight ? boldItalic : italic).setFontSize(9)
                        .setTextAlignment(TextAlignment.RIGHT)));
    }

    private Cell condRow(String label, String value, PdfFont lf, PdfFont vf) {
        return new Cell().setBackgroundColor(LIGHT_BLUE)
                .setBorder(new SolidBorder(NAVY_LIGHT, 0.5f)).setPadding(5)
                .add(new Paragraph()
                        .add(new Text(label + "  ").setFont(lf).setFontSize(8).setFontColor(NAVY))
                        .add(new Text(value).setFont(vf).setFontSize(8)));
    }

    private String safe(Rfq rfq, java.util.function.Function<Rfq, String> getter) {
        if (rfq == null) return "-";
        String v = getter.apply(rfq);
        return (v != null && !v.isBlank()) ? v : "-";
    }

    private String money(BigDecimal v) {
        return v != null ? String.format(new Locale("es", "PE"), "%,.2f", v) : "0,00";
    }

    private String num(BigDecimal v) {
        return v != null ? v.stripTrailingZeros().toPlainString() : "0";
    }

    // ── Azure client ──────────────────────────────────────────────────────────

    private BlobServiceClient buildClient() {
        return new BlobServiceClientBuilder()
                .connectionString(connectionString)
                .buildClient();
    }
}
