package io.justinbeaver.api;

import java.util.Map;

import io.justinbeaver.desktop.DesktopLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** What the page needs to know about the app it runs in. */
@RestController
@RequestMapping("/api/app")
public class AppController {

    private final DesktopLifecycle desktop;

    public AppController(DesktopLifecycle desktop) {
        this.desktop = desktop;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        String version = AppController.class.getPackage().getImplementationVersion();
        return Map.of("app", "justinbeaver", "version", version == null ? "dev" : version, "desktop", desktop.enabled());
    }

    /** Held open by every tab in desktop mode: the server quits when none is left. */
    @GetMapping(path = "/session", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter session() {
        return desktop.attach();
    }

    @PostMapping("/quit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void quit() {
        if (!desktop.enabled()) {
            throw new IllegalArgumentException("Quit is only available in the installed app");
        }
        desktop.quit();
    }
}
