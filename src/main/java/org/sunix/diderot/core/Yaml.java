package org.sunix.diderot.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

/** YAML read/write for the manifest and lockfile. */
public final class Yaml {

    private static final ObjectMapper MAPPER = new ObjectMapper(
            YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                    .build())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private Yaml() {
    }

    public static <T> T read(Path file, Class<T> type) throws IOException {
        return MAPPER.readValue(Files.readAllBytes(file), type);
    }

    public static void write(Path file, Object value) throws IOException {
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
    }
}
