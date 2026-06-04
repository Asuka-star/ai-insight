package com.aiinsight.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SourceEncodingGuardTest {

    private static final List<String> SOURCE_ROOTS = List.of(
            "src/main/java",
            "src/test/java",
            "frontend/src"
    );
    private static final Set<String> TEXT_EXTENSIONS = Set.of(".java", ".ts", ".tsx", ".css");
    private static final List<String> MOJIBAKE_MARKERS = List.of(
            "\u9352", "\u934d", "\u95bb", "\u9422", "\u5a34", "\u93bc", "\u940e",
            "\u7d31", "\u7ef1", "\u9286", "\u9227", "\u00c3", "\u00c2", "\ufffd",
            marker(0x9357), marker(0x7039), marker(0x59af), marker(0x7d21),
            marker(0x93c2), marker(0x5fc4), marker(0x7d94), marker(0x95f9),
            marker(0x7c8e), marker(0x934c), marker(0x7d12), marker(0x93be),
            marker(0x4eaf)
    );

    private static String marker(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    @Test
    void sourceFilesStayUtf8AndDoNotAddMojibake() throws IOException {
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        Properties baseline = loadBaseline();
        List<String> violations = new ArrayList<>();
        for (Path file : sourceFiles(projectRoot)) {
            String relative = projectRoot.relativize(file).toString().replace('\\', '/');
            byte[] bytes = Files.readAllBytes(file);
            if (hasUtf8Bom(bytes)) {
                violations.add(relative + " starts with a UTF-8 BOM");
                continue;
            }
            String text = decodeStrictUtf8(relative, bytes, violations);
            if (text == null) {
                continue;
            }
            int current = mojibakeScore(text);
            int allowed = Integer.parseInt(baseline.getProperty(relative, "0"));
            if (current > allowed) {
                violations.add(relative + " mojibake score increased from " + allowed + " to " + current);
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void mojibakeMarkersCatchKnownSearchResultCorruption() {
        assertThat(mojibakeScore(knownMojibakeSearchResult())).isPositive();
        assertThat(mojibakeScore("\u641c\u7d22\u7ed3\u679c")).isZero();
    }

    private static String knownMojibakeSearchResult() {
        return marker(0x95f9)
                + "\u517c"
                + marker(0x7c8e)
                + marker(0x934c)
                + "\u3127"
                + marker(0x7d12)
                + marker(0x93be)
                + "\u5bf8"
                + marker(0x4eaf);
    }

    private static Properties loadBaseline() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = SourceEncodingGuardTest.class.getResourceAsStream("/encoding-mojibake-baseline.properties")) {
            assertThat(input).as("encoding mojibake baseline resource").isNotNull();
            properties.load(input);
        }
        return properties;
    }

    private static List<Path> sourceFiles(Path projectRoot) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String root : SOURCE_ROOTS) {
            Path sourceRoot = projectRoot.resolve(root);
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (var stream = Files.walk(sourceRoot)) {
                stream.filter(Files::isRegularFile)
                        .filter(SourceEncodingGuardTest::isTextSource)
                        .forEach(files::add);
            }
        }
        return files;
    }

    private static boolean isTextSource(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return TEXT_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf;
    }

    private static String decodeStrictUtf8(String relative, byte[] bytes, List<String> violations) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (IOException ex) {
            violations.add(relative + " is not valid UTF-8: " + ex.getMessage());
            return null;
        }
    }

    private static int mojibakeScore(String text) {
        int score = 0;
        for (String marker : MOJIBAKE_MARKERS) {
            int index = text.indexOf(marker);
            while (index >= 0) {
                score++;
                index = text.indexOf(marker, index + marker.length());
            }
        }
        return score;
    }
}
