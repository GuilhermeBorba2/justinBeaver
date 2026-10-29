package io.justinbeaver;

import io.justinbeaver.desktop.DesktopLauncher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class JustinBeaverApplication {

    public static void main(String[] args) {
        if (Boolean.getBoolean("justinbeaver.desktop.enabled")) {
            // Installed app: a second launch just brings up the page of the one already running.
            if (!Boolean.getBoolean("justinbeaver.desktop.managed") && DesktopLauncher.handOverToRunningInstance()) {
                return;
            }
            // a free port, and a log file (there is no console to read)
            System.setProperty("server.port", System.getProperty("server.port", "0"));
            System.setProperty("logging.file.name", System.getProperty("logging.file.name",
                    DesktopLauncher.dataDir().resolve("logs").resolve("justinbeaver.log").toString()));
            System.setProperty("logging.logback.rollingpolicy.max-file-size", System.getProperty("logging.logback.rollingpolicy.max-file-size", "5MB"));
            System.setProperty("logging.logback.rollingpolicy.max-history", System.getProperty("logging.logback.rollingpolicy.max-history", "5"));
        }
        SpringApplication.run(JustinBeaverApplication.class, args);
    }
}
