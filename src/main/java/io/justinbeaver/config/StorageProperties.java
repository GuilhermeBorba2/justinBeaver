package io.justinbeaver.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where saved connections and the encryption key live. */
@ConfigurationProperties("justinbeaver.storage")
public record StorageProperties(Path dir) {

    public Path dirOrDefault() {
        return dir != null ? dir : Path.of(System.getProperty("user.home"), ".justinbeaver");
    }
}
