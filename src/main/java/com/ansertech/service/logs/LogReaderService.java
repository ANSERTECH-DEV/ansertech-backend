package com.ansertech.service.logs;

import com.ansertech.config.GraphConfiguration;
import com.ansertech.dto.response.GraphConnectionResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee el final del archivo de log ({@code logging.file.name}) sin cargarlo entero en memoria:
 * recorre el archivo desde el final hacia atrás en bloques de 8 KB.
 */
@Service
public class LogReaderService {

    public static final int MAX_LINES = 500;
    private static final int CHUNK = 8 * 1024;

    /** Línea que escribe {@code GraphConfiguration}: "[MICROSOFT_DEVICE_CODE] URL: … | CODE: … | EXPIRA: … | MSG: …". */
    private static final Pattern DEVICE_CODE_LINE = Pattern.compile(
            Pattern.quote(GraphConfiguration.DEVICE_CODE_LOG_TAG)
                    + " URL: (\\S+) \\| CODE: (\\S+) \\| EXPIRA: (\\S+) \\| MSG: (.*)$");

    private final Path logFile;
    private final Clock clock;

    public LogReaderService(@Value("${logging.file.name:logs/ansertech-backend.log}") String logFile, Clock clock) {
        this.logFile = Path.of(logFile);
        this.clock = clock;
    }

    public Path getLogFile() {
        return logFile;
    }

    public boolean exists() {
        return Files.isRegularFile(logFile);
    }

    /** Últimas {@code lines} líneas (1..{@value #MAX_LINES}), en orden cronológico. Vacío si no hay archivo. */
    public List<String> tail(int lines) throws IOException {
        int wanted = Math.max(1, Math.min(lines, MAX_LINES));
        if (!exists()) {
            return List.of();
        }
        try (RandomAccessFile file = new RandomAccessFile(logFile.toFile(), "r")) {
            long pos = file.length();
            List<String> result = new ArrayList<>();
            ByteArrayOutputStream current = new ByteArrayOutputStream();
            boolean skipTrailingNewline = true;
            byte[] buffer = new byte[CHUNK];

            while (pos > 0 && result.size() < wanted) {
                int len = (int) Math.min(CHUNK, pos);
                pos -= len;
                file.seek(pos);
                file.readFully(buffer, 0, len);
                for (int i = len - 1; i >= 0 && result.size() < wanted; i--) {
                    byte b = buffer[i];
                    if (b == '\n') {
                        if (skipTrailingNewline && current.size() == 0 && result.isEmpty()) {
                            skipTrailingNewline = false;
                            continue;
                        }
                        result.add(reversedLine(current));
                        current.reset();
                    } else if (b != '\r') {
                        current.write(b);
                    }
                    skipTrailingNewline = false;
                }
            }
            if (result.size() < wanted && current.size() > 0) {
                result.add(reversedLine(current));
            }
            Collections.reverse(result);
            return result;
        }
    }

    /**
     * Último código de vinculación de Microsoft que siga vigente según el log, por si el estado en
     * memoria no lo tiene (p. ej. la página se abrió en otra pestaña antes de que la instancia lo capturara).
     */
    public Optional<GraphConnectionResponse> findLatestDeviceCode() {
        List<String> lines;
        try {
            lines = tail(MAX_LINES);
        } catch (IOException e) {
            return Optional.empty();
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            Matcher m = DEVICE_CODE_LINE.matcher(lines.get(i));
            if (!m.find()) continue;
            OffsetDateTime expiresOn;
            try {
                expiresOn = OffsetDateTime.parse(m.group(3));
            } catch (DateTimeParseException e) {
                return Optional.empty();
            }
            if (!expiresOn.isAfter(OffsetDateTime.now(clock))) {
                return Optional.empty();  // el más reciente ya venció: los anteriores también
            }
            return Optional.of(GraphConnectionResponse.builder()
                    .status("PENDING")
                    .url(m.group(1))
                    .code(m.group(2))
                    .expiresOn(expiresOn)
                    .rawMessage(m.group(4).trim())
                    .source("LOG")
                    .build());
        }
        return Optional.empty();
    }

    private static String reversedLine(ByteArrayOutputStream reversedBytes) {
        byte[] bytes = reversedBytes.toByteArray();
        for (int i = 0, j = bytes.length - 1; i < j; i++, j--) {
            byte t = bytes[i];
            bytes[i] = bytes[j];
            bytes[j] = t;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
