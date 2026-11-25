package com.ansertech.service.email;

import com.ansertech.service.email.EmailPollingService.AttachmentInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentSelectorTest {

    private static AttachmentInfo a(String name, String mime, long size, boolean inline) {
        return new AttachmentInfo("id-" + name, name, mime, size, inline);
    }

    @Test
    void ignoraElLogoDeLaFirmaYTomaTodosLosPdfEImagenes() {
        var sel = AttachmentSelector.select(List.of(
                a("image001.png", "image/png", 4_000, true),       // logo de firma, primero en la lista
                a("SC_02.pdf", "application/pdf", 2_559, false),
                a("foto.JPG", "image/jpg", 300_000, false)));

        assertThat(sel.selected()).extracting(AttachmentInfo::name).containsExactly("SC_02.pdf", "foto.JPG");
        assertThat(sel.selected().get(1).mimeType()).isEqualTo("image/jpeg");
        assertThat(sel.skipped()).singleElement().asString().contains("image001.png");
    }

    @Test
    void deduceElTipoPorExtensionSiOutlookNoLoInformaYDescartaLosNoSoportados() {
        var sel = AttachmentSelector.select(List.of(
                a("cotizacion.pdf", null, 1_000, false),
                a("lista.pdf", "application/octet-stream", 1_000, false),
                a("pedido.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 1_000, false),
                a("anim.gif", "image/gif", 1_000, false)));

        assertThat(sel.selected()).extracting(AttachmentInfo::name).containsExactly("cotizacion.pdf", "lista.pdf");
        assertThat(sel.selected()).extracting(AttachmentInfo::mimeType).containsOnly("application/pdf");
        assertThat(sel.skipped()).hasSize(2);
    }

    @Test
    void respetaLosLimitesDeTamanoYCantidad() {
        List<AttachmentInfo> many = new ArrayList<>();
        many.add(a("grande.pdf", "application/pdf", AttachmentSelector.MAX_FILE_BYTES + 1, false));
        for (int i = 1; i <= 7; i++) many.add(a("p" + i + ".pdf", "application/pdf", 1_000, false));

        var sel = AttachmentSelector.select(many);

        assertThat(sel.selected()).hasSize(AttachmentSelector.MAX_FILES);
        assertThat(sel.skipped()).anyMatch(r -> r.contains("grande.pdf"));
    }

    @Test
    void sinAdjuntosDevuelveVacio() {
        assertThat(AttachmentSelector.select(null).selected()).isEmpty();
    }
}
