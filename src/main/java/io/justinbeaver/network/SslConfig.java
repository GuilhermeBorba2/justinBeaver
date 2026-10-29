package io.justinbeaver.network;

/**
 * Driver-independent SSL/TLS settings, translated to each driver's properties by {@link SslProperties}.
 *
 * @param mode           {@code REQUIRE}: encrypt without checking the certificate; {@code VERIFY_CA}: check it
 *                       is signed by the CA; {@code VERIFY_FULL}: also check the host name
 * @param caCertPath     CA certificate (PEM), or a JKS/PKCS#12 truststore for drivers that need one
 * @param clientCertPath client certificate for mutual TLS (optional)
 * @param clientKeyPath  client private key for mutual TLS (optional)
 */
public record SslConfig(boolean enabled, Mode mode, String caCertPath, String clientCertPath, String clientKeyPath) {

    public enum Mode { REQUIRE, VERIFY_CA, VERIFY_FULL }

    public Mode modeOrDefault() {
        return mode == null ? Mode.VERIFY_FULL : mode;
    }
}
