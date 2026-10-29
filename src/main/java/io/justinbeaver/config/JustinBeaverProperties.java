package io.justinbeaver.config;

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Mirrors the knobs DBeaver spreads over its registry bundle: where extra driver
 * descriptors live and which Maven repositories driver JARs are fetched from.
 */
@ConfigurationProperties("justinbeaver")
public record JustinBeaverProperties(
        @DefaultValue Drivers drivers,
        @DefaultValue Maven maven) {

    /** @param extraDir optional directory with user-supplied descriptors (*.yml); overrides bundled ids */
    public record Drivers(Path extraDir) {
    }

    /**
     * @param localRepo    cache directory for downloaded driver JARs
     * @param repositories remote repositories, tried in order; Maven Central when empty
     */
    public record Maven(Path localRepo, @DefaultValue List<Repository> repositories) {

        public Path localRepoOrDefault() {
            return localRepo != null
                    ? localRepo
                    : Path.of(System.getProperty("user.home"), ".justinbeaver", "driver-cache");
        }

        public List<Repository> repositoriesOrDefault() {
            return repositories.isEmpty()
                    ? List.of(new Repository("maven-central", "https://repo1.maven.org/maven2/"))
                    : repositories;
        }
    }

    public record Repository(String id, String url) {
    }
}
