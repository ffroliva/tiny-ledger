package com.ffroliva.tinyledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The four {@code application*.properties} files must contain <strong>no byte above 0x7F</strong>.
 *
 * <p>This is not tidiness. {@code java.util.Properties#load(InputStream)} is specified as
 * <strong>ISO-8859-1</strong>, and although Boot's own loader reads UTF-8, every other tool that opens these
 * files picks its own answer — IntelliJ has a setting for properties files that overrides {@code
 * .editorconfig}'s {@code charset = utf-8} entirely. The symptom that prompted this: em dashes, section signs
 * and box-drawing characters rendering as {@code â€"}, {@code Â§} and {@code â”€} for a reader whose editor
 * decoded them as Latin-1. The files were correct UTF-8 the whole time; correctness that depends on every
 * reader's configuration is not correctness, so the characters went instead — 133 of them, all in comments,
 * so nothing functional moved.
 *
 * <p>Restricted to {@code .properties} deliberately. Markdown and Java are UTF-8 everywhere by convention and
 * by this project's {@code .editorconfig}; the properties format is the one with a conflicting specification.
 *
 * <p><strong>Proof it can fail:</strong> reinstate a single em dash in any comment in any of these files and
 * this test reports that file and the offending character. Verified by doing exactly that before committing.
 */
class PropertiesAreAsciiTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");

    @Test
    void applicationPropertiesContainNoNonAsciiCharacter() throws IOException {
        try (Stream<Path> files = Files.list(RESOURCES)) {
            List<Path> properties = files.filter(p -> p.getFileName().toString().endsWith(".properties"))
                    .sorted()
                    .toList();

            // The control. A glob that matched nothing would make every assertion below vacuous — the
            // shape AGENTS.md trap 7 exists for. Four files ship today; fewer means the search broke.
            assertThat(properties)
                    .as("properties files found under %s", RESOURCES)
                    .hasSizeGreaterThanOrEqualTo(4);

            for (Path file : properties) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                String offenders = text.chars()
                        .filter(c -> c > 0x7F)
                        .distinct()
                        .mapToObj(c -> "U+%04X '%c'".formatted(c, c))
                        .toList()
                        .toString();
                assertThat(text.chars().noneMatch(c -> c > 0x7F))
                        .as("%s must be ASCII-only; found %s", file.getFileName(), offenders)
                        .isTrue();
            }
        }
    }
}
