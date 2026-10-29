package io.justinbeaver.api;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import io.justinbeaver.script.ScriptStore;
import io.justinbeaver.script.ScriptStore.Script;
import io.justinbeaver.script.ScriptStore.ScriptInfo;
import io.justinbeaver.script.WorkspaceStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Saved SQL scripts (the "Scripts" folder) and the open-tabs workspace. */
@RestController
public class ScriptController {

    public record ScriptInput(String connectionId, String content) {
    }

    public record RenameRequest(@NotBlank String name) {
    }

    private final ScriptStore scripts;
    private final WorkspaceStore workspace;

    public ScriptController(ScriptStore scripts, WorkspaceStore workspace) {
        this.scripts = scripts;
        this.workspace = workspace;
    }

    @GetMapping("/api/scripts")
    public List<ScriptInfo> list() {
        return scripts.list();
    }

    @PostMapping("/api/scripts")
    @ResponseStatus(HttpStatus.CREATED)
    public Script create(@RequestBody ScriptInput input) {
        return scripts.create(input.connectionId(), input.content());
    }

    @GetMapping("/api/scripts/{name}")
    public Script get(@PathVariable String name) {
        return scripts.get(name);
    }

    @PutMapping("/api/scripts/{name}")
    public ScriptInfo save(@PathVariable String name, @RequestBody ScriptInput input) {
        Script saved = scripts.save(name, input.connectionId(), input.content());
        return new ScriptInfo(saved.name(), saved.connectionId(), saved.content().length(), System.currentTimeMillis());
    }

    @PostMapping("/api/scripts/{name}/rename")
    public ScriptInfo rename(@PathVariable String name, @Valid @RequestBody RenameRequest request) {
        return scripts.rename(name, request.name().strip());
    }

    @DeleteMapping("/api/scripts/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name) {
        scripts.delete(name);
    }

    @GetMapping("/api/workspace")
    public ResponseEntity<JsonNode> workspace() {
        JsonNode saved = workspace.get();
        return saved == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(saved);
    }

    @PutMapping("/api/workspace")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveWorkspace(@RequestBody JsonNode document) {
        workspace.save(document);
    }
}
