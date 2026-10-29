package io.justinbeaver.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.justinbeaver.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptStoreTest {

    @Test
    void scriptsArePlainSqlFilesNumberedAfterTheHighest(@TempDir Path dir) throws Exception {
        ScriptStore store = new ScriptStore(new StorageProperties(dir));

        assertThat(store.create("c1", "select 1").name()).isEqualTo("Script-1");
        store.save("Script-7", "c2", "select 7");
        assertThat(store.create(null, "").name()).isEqualTo("Script-8");

        assertThat(Files.readString(dir.resolve("scripts/Script-1.sql"))).isEqualTo("select 1");
        ScriptStore reopened = new ScriptStore(new StorageProperties(dir));
        assertThat(reopened.get("Script-7").connectionId()).isEqualTo("c2");
        assertThat(reopened.get("Script-8").connectionId()).isNull();
        assertThat(reopened.list()).extracting(ScriptStore.ScriptInfo::name)
                .containsExactlyInAnyOrder("Script-1", "Script-7", "Script-8");
    }

    @Test
    void renameKeepsTheConnectionAndRefusesTakenNames(@TempDir Path dir) {
        ScriptStore store = new ScriptStore(new StorageProperties(dir));
        store.save("a", "c1", "select 1");
        store.save("b", "c2", "select 2");

        assertThatThrownBy(() -> store.rename("a", "b")).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.rename("a", "monthly report").connectionId()).isEqualTo("c1");
        assertThat(store.get("monthly report").content()).isEqualTo("select 1");
        assertThatThrownBy(() -> store.get("a")).isInstanceOf(ScriptNotFoundException.class);

        store.delete("monthly report");
        assertThat(store.list()).extracting(ScriptStore.ScriptInfo::name).containsExactly("b");
    }

    @Test
    void namesCannotEscapeTheScriptsFolder(@TempDir Path dir) {
        ScriptStore store = new ScriptStore(new StorageProperties(dir));

        for (String name : new String[] {"../x", "a/b", "..", ".index", " padded", "", "a\\b"}) {
            assertThatThrownBy(() -> store.save(name, null, "x")).as(name).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Files.exists(dir.resolve("x.sql"))).isFalse();
    }

    @Test
    void workspaceRoundTripsAnyJsonObject(@TempDir Path dir) throws Exception {
        WorkspaceStore store = new WorkspaceStore(new StorageProperties(dir));
        assertThat(store.get()).isNull();

        store.save(new ObjectMapper().readTree("{\"tabs\":[{\"id\":\"t1\"}],\"activeId\":\"t1\"}"));

        assertThat(new WorkspaceStore(new StorageProperties(dir)).get().path("activeId").asText()).isEqualTo("t1");
        assertThatThrownBy(() -> store.save(new ObjectMapper().readTree("[1]"))).isInstanceOf(IllegalArgumentException.class);
    }
}
