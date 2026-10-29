package io.justinbeaver.google;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Google sign-in and Drive endpoints. The client id of a Google Cloud "Desktop app" OAuth client is baked into the
 * installer ({@code -Djustinbeaver.google.client-id}) or configured through {@code JB_GOOGLE_CLIENT_ID} for development.
 * Existing {@code google-client.json} files remain supported. The URLs are
 * Google's own unless overridden (tests point them at a local mock).
 */
@ConfigurationProperties("justinbeaver.google")
public record GoogleProperties(String clientId, String clientSecret, String authUrl, String tokenUrl, String userInfoUrl,
                               String driveUrl, String uploadUrl, String revokeUrl) {

    public String authUrlOrDefault() {
        return authUrl != null ? authUrl : "https://accounts.google.com/o/oauth2/v2/auth";
    }

    public String tokenUrlOrDefault() {
        return tokenUrl != null ? tokenUrl : "https://oauth2.googleapis.com/token";
    }

    public String userInfoUrlOrDefault() {
        return userInfoUrl != null ? userInfoUrl : "https://openidconnect.googleapis.com/v1/userinfo";
    }

    public String driveUrlOrDefault() {
        return driveUrl != null ? driveUrl : "https://www.googleapis.com/drive/v3";
    }

    public String uploadUrlOrDefault() {
        return uploadUrl != null ? uploadUrl : "https://www.googleapis.com/upload/drive/v3";
    }

    public String revokeUrlOrDefault() {
        return revokeUrl != null ? revokeUrl : "https://oauth2.googleapis.com/revoke";
    }
}
