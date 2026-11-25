package com.ansertech.service.email;

import com.ansertech.service.email.EmailPollingService.AttachmentInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decide qué adjuntos de un correo se envían a Gemini: solo PDF e imágenes que Gemini acepta, sin los
 * incrustados en el cuerpo (logos de firma) y dentro de los límites de tamaño de una petición a Vertex AI.
 */
public final class AttachmentSelector {

    /** Tipos que Gemini acepta como inlineData. */
    static final List<String> SUPPORTED = List.of(
            "application/pdf", "image/png", "image/jpeg", "image/webp", "image/heic", "image/heif");

    private static final Map<String, String> BY_EXTENSION = Map.of(
            "pdf", "application/pdf", "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "webp", "image/webp", "heic", "image/heic", "heif", "image/heif");

    public static final int MAX_FILES = 5;
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;   // 10 MB por archivo
    public static final long MAX_TOTAL_BYTES = 15L * 1024 * 1024;  // la petición a Vertex AI admite ~20 MB

    private AttachmentSelector() {}

    public record Selection(List<AttachmentInfo> selected, List<String> skipped) {}

    public static Selection select(List<AttachmentInfo> attachments) {
        List<AttachmentInfo> selected = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        long total = 0;
        if (attachments == null) return new Selection(selected, skipped);

        for (AttachmentInfo a : attachments) {
            String name = a.name() != null ? a.name() : "(sin nombre)";
            String mime = effectiveMimeType(a);
            if (a.inline()) { skipped.add(name + ": incrustado en el cuerpo (firma/logo)"); continue; }
            if (mime == null) { skipped.add(name + ": tipo no soportado (" + a.mimeType() + ")"); continue; }
            if (a.size() > MAX_FILE_BYTES) { skipped.add(name + ": supera 10 MB"); continue; }
            if (selected.size() >= MAX_FILES) { skipped.add(name + ": máximo " + MAX_FILES + " archivos"); continue; }
            if (total + a.size() > MAX_TOTAL_BYTES) { skipped.add(name + ": se supera el total de 15 MB"); continue; }
            total += a.size();
            selected.add(new AttachmentInfo(a.id(), a.name(), mime, a.size(), false));
        }
        return new Selection(selected, skipped);
    }

    /** Tipo MIME aceptado por Gemini, deducido de la extensión si Outlook manda uno genérico o ninguno; null si no aplica. */
    static String effectiveMimeType(AttachmentInfo a) {
        String mime = a.mimeType() != null ? a.mimeType().toLowerCase(Locale.ROOT).split(";")[0].trim() : "";
        if (mime.equals("image/jpg") || mime.equals("image/pjpeg")) mime = "image/jpeg";
        if (SUPPORTED.contains(mime)) return mime;
        if (mime.isEmpty() || mime.equals("application/octet-stream")) {
            String n = a.name() != null ? a.name().toLowerCase(Locale.ROOT) : "";
            int dot = n.lastIndexOf('.');
            return dot >= 0 ? BY_EXTENSION.get(n.substring(dot + 1)) : null;
        }
        return null;
    }
}
