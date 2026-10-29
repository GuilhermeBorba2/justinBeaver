package io.justinbeaver.jdbc;

public record ConnectionTestResult(
        String productName, String productVersion, String driverName, String driverVersion) {
}
