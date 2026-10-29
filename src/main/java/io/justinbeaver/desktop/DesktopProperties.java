package io.justinbeaver.desktop;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Desktop mode uses a free loopback port. With {@code managed=true}, Electron owns the window and lifetime;
 * otherwise the standalone Java launcher opens a browser and stops when its last tab closes.
 *
 * @param enabled       desktop mode on
 * @param idleSeconds   how long the server waits without any open tab before it quits
 * @param startSeconds  how long it waits for the first tab after starting
 * @param managed       Electron owns startup and shutdown through the parent process pipes
 */
@ConfigurationProperties("justinbeaver.desktop")
public record DesktopProperties(boolean enabled, Integer idleSeconds, Integer startSeconds, boolean managed) {

    public int idleSecondsOrDefault() {
        return idleSeconds == null ? 20 : Math.max(1, idleSeconds);
    }

    public int startSecondsOrDefault() {
        return startSeconds == null ? 90 : Math.max(1, startSeconds);
    }
}
