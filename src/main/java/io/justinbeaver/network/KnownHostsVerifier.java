package io.justinbeaver.network;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;

import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.common.SecurityUtils;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts;
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts.HostEntry;
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts.KnownHostEntry;

/**
 * Host key check against the user's {@code ~/.ssh/known_hosts} (read-only) and JustinBeaver's own
 * {@code known_hosts}. Unknown hosts are trusted on first use and remembered when allowed; a key that
 * differs from a remembered one is always rejected, since that is what a man-in-the-middle looks like.
 */
final class KnownHostsVerifier implements HostKeyVerifier {

    private final List<OpenSSHKnownHosts> sources = new ArrayList<>();
    private final OpenSSHKnownHosts appHosts;
    private final boolean acceptNew;
    private volatile String failure;

    KnownHostsVerifier(Path appKnownHosts, Path userKnownHosts, boolean acceptNew) throws IOException {
        if (userKnownHosts != null && Files.isRegularFile(userKnownHosts)) {
            sources.add(new OpenSSHKnownHosts(userKnownHosts.toFile()));
        }
        Files.createDirectories(appKnownHosts.getParent());
        if (!Files.exists(appKnownHosts)) {
            Files.createFile(appKnownHosts);
        }
        appHosts = new OpenSSHKnownHosts(appKnownHosts.toFile());
        sources.add(appHosts);
        this.acceptNew = acceptNew;
    }

    /** Why verification failed, for a readable error. */
    String failure() {
        return failure;
    }

    @Override
    public boolean verify(String hostname, int port, PublicKey key) {
        String host = port == 22 ? hostname : "[" + hostname + "]:" + port;
        KeyType type = KeyType.fromKey(key);
        boolean known = false;
        try {
            for (OpenSSHKnownHosts source : sources) {
                for (KnownHostEntry entry : source.entries()) {
                    // any remembered key for this host counts, whatever its type: otherwise a server offering a
                    // different key algorithm would be accepted as "new"
                    if (entry.appliesTo(host)) {
                        known = true;
                        if (entry.verify(key)) {
                            return true;
                        }
                    }
                }
            }
            if (known) {
                failure = ("HOST KEY CHANGED for %s (now %s %s). Someone may be intercepting the connection; if the "
                        + "server key really changed, remove its line from %s").formatted(host, type, SecurityUtils.getFingerprint(key),
                        describe());
                return false;
            }
            if (!acceptNew) {
                failure = "Unknown host key for %s (%s %s) and accepting new host keys is off"
                        .formatted(host, type, SecurityUtils.getFingerprint(key));
                return false;
            }
            appHosts.write(new HostEntry(null, host, type, key));
            return true;
        } catch (IOException e) {
            failure = "Cannot check known hosts: " + e.getMessage();
            return false;
        }
    }

    @Override
    public List<String> findExistingAlgorithms(String hostname, int port) {
        List<String> algorithms = new ArrayList<>();
        for (OpenSSHKnownHosts source : sources) {
            algorithms.addAll(source.findExistingAlgorithms(hostname, port));
        }
        return algorithms;
    }

    private String describe() {
        List<String> files = new ArrayList<>();
        for (OpenSSHKnownHosts source : sources) {
            File file = source.getFile();
            if (file != null) {
                files.add(file.getPath());
            }
        }
        return String.join(" or ", files);
    }
}
