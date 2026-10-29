package io.justinbeaver.desktop;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Electron-managed mode announces readiness and exits on parent-pipe EOF. Standalone desktop mode opens a browser
 * once the server is up, and quits when the last tab has been closed for a while.
 * Each open tab holds a server-sent-events connection ({@code /api/app/session}); the connection ending is the signal,
 * which a browser keeps reliable even for tabs in the background.
 */
@Component
public class DesktopLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DesktopLifecycle.class);

    private final DesktopProperties properties;
    private final ApplicationContext context;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "desktop-lifecycle");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<SseEmitter, Boolean> tabs = new ConcurrentHashMap<>();
    private final AtomicInteger port = new AtomicInteger();
    private ScheduledFuture<?> quitTimer;

    public DesktopLifecycle(DesktopProperties properties, ApplicationContext context) {
        this.properties = properties;
        this.context = context;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public int port() {
        return port.get();
    }

    @EventListener
    void onStarted(WebServerInitializedEvent event) {
        if (!properties.enabled()) {
            return;
        }
        port.set(event.getWebServer().getPort());
        if (properties.managed()) {
            // Electron owns the window and the process. stdout announces the randomly assigned port;
            // stdin closes when the parent exits, including a crash, so the Java child cannot linger.
            Thread parent = new Thread(() -> {
                try {
                    while (System.in.read() != -1) { /* wait for parent pipe to close */ }
                } catch (IOException ignored) {
                    // A broken parent pipe has the same meaning as EOF.
                }
                quit();
            }, "desktop-parent");
            parent.setDaemon(true);
            parent.start();
            System.out.println("JUSTINBEAVER_READY=" + port.get());
            System.out.flush();
            return;
        }
        DesktopLauncher.writePort(port.get());
        DesktopLauncher.openBrowser("http://127.0.0.1:" + port.get() + "/");
        scheduleQuit(properties.startSecondsOrDefault());
        log.info("JustinBeaver is open at http://127.0.0.1:{}/ (closes when the browser tab is closed)", port.get());
    }

    /** A tab connected; it stays until its connection ends. */
    public SseEmitter attach() {
        SseEmitter emitter = new SseEmitter(0L);
        synchronized (this) {
            tabs.put(emitter, Boolean.TRUE);
            cancelQuit();
        }
        Runnable gone = () -> detach(emitter);
        emitter.onCompletion(gone);
        emitter.onTimeout(gone);
        emitter.onError(e -> gone.run());
        try {
            emitter.send(SseEmitter.event().name("hello").data("ok"));
        } catch (IOException e) {
            detach(emitter);
        }
        return emitter;
    }

    private synchronized void detach(SseEmitter emitter) {
        tabs.remove(emitter);
        if (tabs.isEmpty() && properties.enabled()) {
            scheduleQuit(properties.idleSecondsOrDefault());
        }
    }

    /** Keeps the connections alive and notices tabs that vanished without closing their connection. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10_000)
    void ping() {
        for (SseEmitter emitter : tabs.keySet()) {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                detach(emitter);
            }
        }
    }

    private synchronized void scheduleQuit(int seconds) {
        if (!properties.enabled() || properties.managed()) {
            return;
        }
        cancelQuit();
        quitTimer = timer.schedule(() -> {
            synchronized (this) {
                if (!tabs.isEmpty()) {
                    return;
                }
            }
            log.info("No browser tab is open: JustinBeaver quits");
            quit();
        }, seconds, TimeUnit.SECONDS);
    }

    private void cancelQuit() {
        if (quitTimer != null) {
            quitTimer.cancel(false);
            quitTimer = null;
        }
    }

    /** Stops the app (the menu's "Quit"). */
    public void quit() {
        timer.execute(() -> {
            if (!properties.managed()) DesktopLauncher.deletePort();
            int code = org.springframework.boot.SpringApplication.exit(context, () -> 0);
            System.exit(code);
        });
    }

    @PreDestroy
    void cleanup() {
        if (properties.enabled() && !properties.managed()) {
            DesktopLauncher.deletePort();
        }
    }
}
