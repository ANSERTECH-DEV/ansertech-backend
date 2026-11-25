package com.ansertech.service.email;

import com.ansertech.service.ai.GeminiService.FilePart;
import com.ansertech.service.email.EmailPollingService.AttachmentInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Descarga de Outlook los adjuntos que sirven para la IA (ver {@link AttachmentSelector}) una sola vez por correo,
 * para usarlos en la clasificación y en la extracción. Un adjunto que no se pueda descargar se omite (no tumba el correo).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttachmentLoader {

    private final EmailPollingService pollingService;

    public List<FilePart> load(String externalMessageId, List<AttachmentInfo> attachments) {
        AttachmentSelector.Selection selection = AttachmentSelector.select(attachments);
        selection.skipped().forEach(reason -> log.info("Adjunto omitido para la IA — {}", reason));

        List<FilePart> files = new ArrayList<>();
        for (AttachmentInfo a : selection.selected()) {
            try {
                byte[] bytes = pollingService.fetchAttachment(externalMessageId, a.id());
                if (bytes == null || bytes.length == 0) {
                    log.warn("Adjunto {} vacío o no descargable; se omite", a.name());
                    continue;
                }
                if (bytes.length > AttachmentSelector.MAX_FILE_BYTES) {
                    log.info("Adjunto omitido para la IA — {}: supera 10 MB", a.name());
                    continue;
                }
                files.add(new FilePart(a.name(), a.mimeType(), bytes));
                log.info("Adjunto para la IA: {} ({}, {} bytes)", a.name(), a.mimeType(), bytes.length);
            } catch (Exception e) {
                log.warn("No se pudo descargar el adjunto {}: {}", a.name(), e.getMessage());
            }
        }
        return files;
    }
}
