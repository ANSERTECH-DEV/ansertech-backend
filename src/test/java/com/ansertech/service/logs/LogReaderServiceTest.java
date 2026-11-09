package com.ansertech.service.logs;

import com.ansertech.dto.response.GraphConnectionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class LogReaderServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T22:00:00Z"), ZoneOffset.UTC);

    @TempDir Path dir;

    @Test
    void missingFileReturnsNoLines() throws Exception {
        LogReaderService service = new LogReaderService(dir.resolve("no-existe.log").toString(), CLOCK);

        assertThat(service.exists()).isFalse();
        assertThat(service.tail(50)).isEmpty();
        assertThat(service.findLatestDeviceCode()).isEmpty();
    }

    @Test
    void tailReturnsLastLinesInOrderAcrossChunks() throws Exception {
        // ~30 KB: obliga a leer varios bloques de 8 KB; incluye acentos (UTF-8 multibyte)
        String content = IntStream.rangeClosed(1, 300)
                .mapToObj(i -> "línea " + i + " " + "x".repeat(90))
                .collect(Collectors.joining("\n", "", "\n"));
        Path file = dir.resolve("app.log");
        Files.writeString(file, content, StandardCharsets.UTF_8);

        List<String> tail = new LogReaderService(file.toString(), CLOCK).tail(3);

        assertThat(tail).hasSize(3);
        assertThat(tail.get(0)).startsWith("línea 298 ");
        assertThat(tail.get(2)).startsWith("línea 300 ");
    }

    @Test
    void tailHandlesFileWithoutTrailingNewlineAndFewerLines() throws Exception {
        Path file = dir.resolve("app.log");
        Files.writeString(file, "uno\r\ndos\r\ntres", StandardCharsets.UTF_8);

        assertThat(new LogReaderService(file.toString(), CLOCK).tail(50)).containsExactly("uno", "dos", "tres");
    }

    @Test
    void findsLatestLiveDeviceCode() throws Exception {
        Path file = dir.resolve("app.log");
        Files.writeString(file, String.join("\n",
                "2026-11-08T16:40:00.000-05:00  WARN 1 --- [graph] c.a.c.GraphConfiguration : [MICROSOFT_DEVICE_CODE] URL: https://microsoft.com/devicelogin | CODE: OLD11111 | EXPIRA: 2026-11-08T21:55:00Z | MSG: viejo",
                "2026-11-08T16:50:00.000-05:00  WARN 1 --- [graph] c.a.c.GraphConfiguration : [MICROSOFT_DEVICE_CODE] URL: https://microsoft.com/devicelogin | CODE: ABCD2345 | EXPIRA: 2026-11-08T22:05:00Z | MSG: To sign in, use a web browser to open the page https://microsoft.com/devicelogin and enter the code ABCD2345 to authenticate.",
                "2026-11-08T16:50:01.000-05:00  INFO 1 --- otra línea"), StandardCharsets.UTF_8);

        Optional<GraphConnectionResponse> found = new LogReaderService(file.toString(), CLOCK).findLatestDeviceCode();

        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo("ABCD2345");
        assertThat(found.get().getUrl()).isEqualTo("https://microsoft.com/devicelogin");
        assertThat(found.get().getStatus()).isEqualTo("PENDING");
        assertThat(found.get().getSource()).isEqualTo("LOG");
        assertThat(found.get().getRawMessage()).startsWith("To sign in");
    }

    @Test
    void expiredDeviceCodeIsIgnored() throws Exception {
        Path file = dir.resolve("app.log");
        Files.writeString(file,
                "[MICROSOFT_DEVICE_CODE] URL: https://microsoft.com/devicelogin | CODE: OLD11111 | EXPIRA: 2026-11-08T21:59:59Z | MSG: viejo\n",
                StandardCharsets.UTF_8);

        assertThat(new LogReaderService(file.toString(), CLOCK).findLatestDeviceCode()).isEmpty();
    }
}
