package io.justinbeaver.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.justinbeaver.config.JustinBeaverProperties;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.springframework.stereotype.Component;

/**
 * Downloads a driver JAR plus its runtime dependencies into a private cache and returns the files
 * in classpath order (DBeaver's {@code MavenRegistry}). Coordinates are plain Maven:
 * {@code group:artifact:version} or {@code group:artifact:ext:version}.
 * <p>
 * Optional transitive dependencies and test/provided scopes are skipped, which matches how a
 * driver is normally consumed.
 */
@Component
public class MavenResolver {

    private final Path localRepo;
    private final List<RemoteRepository> repositories;

    private volatile RepositorySystem system;

    public MavenResolver(JustinBeaverProperties properties) {
        this.localRepo = properties.maven().localRepoOrDefault();
        this.repositories = properties.maven().repositoriesOrDefault().stream()
                .map(r -> new RemoteRepository.Builder(r.id(), "default", r.url()).build())
                .toList();
    }

    /** Resolve one coordinate and its transitive runtime closure. Blocking; may download. */
    public synchronized List<Path> resolve(String coordinate) throws MavenResolutionException {
        RepositorySystem repoSystem = repositorySystem();
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        session.setLocalRepositoryManager(repoSystem.newLocalRepositoryManager(session, new LocalRepository(localRepo.toFile())));
        session.setChecksumPolicy("fail");

        DefaultArtifact artifact;
        try {
            artifact = new DefaultArtifact(coordinate);
        } catch (IllegalArgumentException e) {
            throw new MavenResolutionException("Invalid Maven coordinate '%s'".formatted(coordinate), e);
        }
        if ("RELEASE".equals(artifact.getVersion()) || "LATEST".equals(artifact.getVersion())) {
            artifact = (DefaultArtifact) artifact.setVersion(newestStable(repoSystem, session, artifact));
        }

        CollectRequest collect = new CollectRequest(new Dependency(artifact, JavaScopes.RUNTIME), repositories);
        DependencyRequest request = new DependencyRequest(collect,
                DependencyFilterUtils.classpathFilter(JavaScopes.RUNTIME));
        try {
            List<Path> files = new ArrayList<>();
            for (ArtifactResult result : repoSystem.resolveDependencies(session, request).getArtifactResults()) {
                files.add(result.getArtifact().getFile().toPath());
            }
            return files;
        } catch (DependencyResolutionException e) {
            throw new MavenResolutionException("Cannot resolve '%s': %s".formatted(coordinate, e.getMessage()), e);
        }
    }

    private static final java.util.regex.Pattern PRE_RELEASE = java.util.regex.Pattern.compile(
            "(?i).*(alpha|beta|rc\\d*|cr\\d*|snapshot|preview|incubating|milestone|[.-]m\\d+|[.-]ea\\d*)\\b.*");

    /** "RELEASE"/"LATEST" (as used in DBeaver's driver catalog): the newest version that is not a pre-release. */
    private String newestStable(RepositorySystem repoSystem, DefaultRepositorySystemSession session, DefaultArtifact artifact)
            throws MavenResolutionException {
        try {
            var request = new org.eclipse.aether.resolution.VersionRangeRequest(artifact.setVersion("[0,)"), repositories, null);
            var versions = new java.util.ArrayList<>(repoSystem.resolveVersionRange(session, request).getVersions());
            java.util.Collections.reverse(versions);
            for (var version : versions) {
                if (!PRE_RELEASE.matcher(version.toString()).matches()) {
                    return version.toString();
                }
            }
            if (!versions.isEmpty()) {
                return versions.get(0).toString();
            }
        } catch (org.eclipse.aether.resolution.VersionRangeResolutionException e) {
            throw new MavenResolutionException("Cannot list versions of " + artifact, e);
        }
        throw new MavenResolutionException("No version of %s:%s found".formatted(artifact.getGroupId(), artifact.getArtifactId()), null);
    }

    private RepositorySystem repositorySystem() {
        RepositorySystem local = system;
        if (local == null) {
            synchronized (this) {
                local = system;
                if (local == null) {
                    system = local = new RepositorySystemSupplier().get();
                }
            }
        }
        return local;
    }
}
