package io.justinbeaver.api;

import java.util.LinkedHashMap;
import java.util.Map;

import io.justinbeaver.google.GoogleAuthService;
import io.justinbeaver.google.GoogleAuthService.Account;
import io.justinbeaver.google.GoogleException;
import io.justinbeaver.google.SyncService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/** Sign in with Google, and the sync of connections and preferences through the user's Drive. */
@RestController
@RequestMapping("/api")
public class GoogleController {

    public record ClientRequest(String clientId, String clientSecret) {
    }

    public record PassphraseRequest(String passphrase) {
    }

    public record SyncRequest(Map<String, String> settings, Long settingsUpdatedAt) {
    }

    /** What to move to or from Drive, and (for a save) the page's preferences. */
    public record TransferRequest(boolean connections, boolean scripts, boolean preferences, Map<String, String> settings, Long settingsUpdatedAt) {
        SyncService.Transfer what() {
            return new SyncService.Transfer(connections, scripts, preferences);
        }
    }

    private final GoogleAuthService auth;
    private final SyncService sync;

    public GoogleController(GoogleAuthService auth, SyncService sync) {
        this.auth = auth;
        this.sync = sync;
    }

    @GetMapping("/google/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", auth.configured());
        Account who = auth.account();
        out.put("signedIn", who != null);
        if (who != null) {
            out.put("email", who.email());
            out.put("name", who.name());
            out.put("picture", who.picture());
        }
        out.put("sync", sync.status());
        return out;
    }

    @PutMapping("/google/client")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveClient(@RequestBody ClientRequest request) {
        auth.saveClient(request.clientId(), request.clientSecret());
    }

    /** The address of Google's consent page; Google redirects back to this server on loopback when the user agrees. */
    @PostMapping("/google/login")
    public Map<String, String> login(HttpServletRequest request) {
        String redirect = "http://127.0.0.1:" + request.getServerPort() + "/api/google/callback";
        return Map.of("url", auth.startLogin(redirect));
    }

    @GetMapping(path = "/google/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                                           @RequestParam(required = false) String error) {
        String body;
        HttpStatus status = HttpStatus.OK;
        try {
            if (error != null || code == null) {
                throw new GoogleException(400, error == null ? "Google sent no code" : "Google: " + error);
            }
            Account who = auth.complete(code, state);
            body = page("Signed in", "You are signed in as <b>" + HtmlUtils.htmlEscape(who.email()) + "</b>. You can close this tab and go back to JustinBeaver.", true);
        } catch (GoogleException e) {
            status = HttpStatus.BAD_REQUEST;
            body = page("Sign-in failed", HtmlUtils.htmlEscape(e.getMessage()) + "<br>Close this tab and try again in JustinBeaver.", false);
        }
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).header("Cache-Control", "no-store").body(body);
    }

    @PostMapping("/google/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        sync.disable();
        auth.signOut();
    }

    @GetMapping("/sync/cloud")
    public Map<String, Boolean> cloudCopy() {
        return Map.of("exists", sync.cloudCopyExists());
    }

    /** Turns sync on for this machine; answers whether the cloud already had a copy (its passphrase was then required). */
    @PostMapping("/sync/enable")
    public Map<String, Boolean> enable(@RequestBody PassphraseRequest request) {
        return Map.of("cloudCopyExisted", sync.enable(request.passphrase()));
    }

    @PostMapping("/sync/now")
    public SyncService.Result now(@RequestBody(required = false) SyncRequest request) {
        return request == null ? sync.sync(null, 0L) : sync.sync(request.settings(), request.settingsUpdatedAt() == null ? 0L : request.settingsUpdatedAt());
    }

    /** "Save to Google Drive": this computer's items replace the cloud's. */
    @PostMapping("/sync/export")
    public SyncService.TransferResult export(@RequestBody TransferRequest request) {
        return sync.export(request.what(), request.settings(), request.settingsUpdatedAt() == null ? 0L : request.settingsUpdatedAt());
    }

    /** "Import from Google Drive": the cloud's items replace this computer's. */
    @PostMapping("/sync/import")
    public SyncService.TransferResult importFromCloud(@RequestBody TransferRequest request) {
        return sync.importFrom(request.what());
    }

    @DeleteMapping("/sync")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable() {
        sync.disable();
    }

    @DeleteMapping("/sync/cloud")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCloud() {
        sync.deleteCloudCopy();
    }

    private static String page(String title, String message, boolean close) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>JustinBeaver · " + title + "</title>"
                + "<style>body{font:16px system-ui,sans-serif;display:grid;place-items:center;height:100vh;margin:0;background:#1b1a18;color:#e8e4de}"
                + "main{max-width:460px;padding:24px;text-align:center}h1{color:#d08a5c;font-size:22px}</style></head><body><main><h1>" + title + "</h1><p>"
                + message + "</p></main>" + (close ? "<script>setTimeout(function(){window.close()},1500)</script>" : "") + "</body></html>";
    }
}
