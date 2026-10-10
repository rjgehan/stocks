package com.stocks.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.config.JacksonConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Golden JSON files under {@code src/test/resources/golden/<area>/} (owners in §27.2). {@link #assertMatches}
 * serializes a value with the §6.3 mapper and compares it structurally with the file. Run with
 * {@code -Dgolden.update=true} to (re)write the files instead, then review the diff.
 */
public final class GoldenFiles {

  public static final Path ROOT = Path.of("src", "test", "resources", "golden");
  private static final JsonMapper MAPPER = JacksonConfig.configure(JsonMapper.builder())
      .enable(SerializationFeature.INDENT_OUTPUT)
      .build();

  private GoldenFiles() {}

  public static boolean updating() {
    return Boolean.getBoolean("golden.update");
  }

  /** Compares {@code actual} (any DTO or a JSON string) with {@code golden/<area>/<name>.json}. */
  public static void assertMatches(String area, String name, Object actual) {
    Path file = ROOT.resolve(area).resolve(name + ".json");
    JsonNode actualTree = actual instanceof String s ? MAPPER.readTree(s) : MAPPER.valueToTree(actual);
    try {
      if (updating() || !Files.exists(file)) {
        if (!updating()) {
          throw new AssertionError("Missing golden file " + file + "; run with -Dgolden.update=true to create it");
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, MAPPER.writeValueAsString(actualTree) + "\n", StandardCharsets.UTF_8);
        return;
      }
      JsonNode expected = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
      assertThat(actualTree).as("golden %s", file).isEqualTo(expected);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Reads a golden file as a JSON tree. */
  public static JsonNode read(String area, String name) {
    try {
      return MAPPER.readTree(Files.readString(ROOT.resolve(area).resolve(name + ".json"), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
