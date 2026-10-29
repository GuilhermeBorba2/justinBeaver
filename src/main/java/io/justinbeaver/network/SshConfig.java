package io.justinbeaver.network;

/**
 * SSH tunnel settings of a connection (DBeaver's SSH network handler). The database host/port of
 * the connection are reached <em>from the SSH server</em>; blank host means the SSH server itself.
 *
 * @param password           for {@link Auth#PASSWORD}; {@code null} on update keeps the stored one
 * @param privateKeyPath     OpenSSH/PEM/PuTTY key file for {@link Auth#KEY}
 * @param passphrase         of the private key; {@code null} on update keeps the stored one
 * @param acceptNewHostKeys  trust and remember a host key seen for the first time (like OpenSSH
 *                           {@code StrictHostKeyChecking=accept-new}); a changed key is always rejected
 */
public record SshConfig(
        boolean enabled,
        String host,
        Integer port,
        String user,
        Auth auth,
        String password,
        String privateKeyPath,
        String passphrase,
        Boolean acceptNewHostKeys) {

    public enum Auth { PASSWORD, KEY }

    public int portOrDefault() {
        return port == null || port <= 0 ? 22 : port;
    }

    public Auth authOrDefault() {
        return auth == null ? Auth.PASSWORD : auth;
    }

    public boolean acceptNewHostKeysOrDefault() {
        return acceptNewHostKeys == null || acceptNewHostKeys;
    }

    public SshConfig withSecrets(String newPassword, String newPassphrase) {
        return new SshConfig(enabled, host, port, user, auth, newPassword, privateKeyPath, newPassphrase, acceptNewHostKeys);
    }

    @Override
    public String toString() {
        return "SshConfig[enabled=%s, host=%s, port=%s, user=%s, auth=%s, password=***, passphrase=***]"
                .formatted(enabled, host, port, user, auth);
    }
}
