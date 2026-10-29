package io.justinbeaver.api;

import java.sql.SQLException;
import java.util.List;

import io.justinbeaver.connection.ConnectionInput;
import io.justinbeaver.connection.ConnectionStore;
import io.justinbeaver.connection.ConnectionView;
import io.justinbeaver.connection.DbeaverImporter;
import io.justinbeaver.connection.SavedConnection;
import io.justinbeaver.session.SessionManager;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** CRUD for saved connections plus connect/disconnect (the navigator's top level). */
@RestController
@RequestMapping("/api/connections")
public class SavedConnectionController {

    private final ConnectionStore store;
    private final SessionManager sessions;
    private final DbeaverImporter importer;

    public SavedConnectionController(ConnectionStore store, SessionManager sessions, DbeaverImporter importer) {
        this.store = store;
        this.sessions = sessions;
        this.importer = importer;
    }

    public record ImportRequest(String dir, @jakarta.validation.constraints.NotNull List<String> ids) {
    }

    /** Connections found in a DBeaver workspace (default: the usual DBeaverData folder of this user). */
    @GetMapping("/import/dbeaver")
    public DbeaverImporter.Preview previewImport(@org.springframework.web.bind.annotation.RequestParam(required = false) String dir)
            throws java.io.IOException {
        return importer.preview(dir);
    }

    @PostMapping("/import/dbeaver")
    public DbeaverImporter.Result importDbeaver(@Valid @RequestBody ImportRequest request) throws java.io.IOException {
        return importer.importConnections(request.dir(), request.ids());
    }

    @GetMapping
    public List<ConnectionView> list() {
        return store.all().stream().map(this::view).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionView create(@Valid @RequestBody ConnectionInput input) {
        return view(store.create(input));
    }

    @PutMapping("/{id}")
    public ConnectionView update(@PathVariable String id, @Valid @RequestBody ConnectionInput input) throws SQLException {
        SavedConnection updated = store.update(id, input);
        sessions.disconnect(id); // settings changed: next connect uses the new ones
        return view(updated);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) throws SQLException {
        store.delete(id);
        sessions.disconnect(id);
    }

    @PostMapping("/{id}/connect")
    public ConnectionView connect(@PathVariable String id) throws SQLException {
        SavedConnection saved = store.get(id);
        sessions.connect(saved);
        return view(saved);
    }

    @PostMapping("/{id}/disconnect")
    public ConnectionView disconnect(@PathVariable String id) throws SQLException {
        SavedConnection saved = store.get(id);
        sessions.disconnect(id);
        return view(saved);
    }

    private ConnectionView view(SavedConnection saved) {
        return ConnectionView.of(saved, sessions.isConnected(saved.id()));
    }
}
