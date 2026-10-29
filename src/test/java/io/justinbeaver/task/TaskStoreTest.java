package io.justinbeaver.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import io.justinbeaver.config.StorageProperties;
import io.justinbeaver.query.QueryModels.Column;
import io.justinbeaver.query.QueryModels.QueryResult;
import io.justinbeaver.task.TaskStore.Run;
import io.justinbeaver.task.TaskStore.Schedule;
import io.justinbeaver.task.TaskStore.ScheduleKind;
import io.justinbeaver.task.TaskStore.Task;
import io.justinbeaver.task.TaskStore.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskStoreTest {

    private static Task task(String id, Schedule schedule) {
        return new Task(id, "nightly", Type.SQL, "c1", "delete from log", null, null, schedule, true, null);
    }

    @Test
    void savesTasksAndKeepsTheLatestRuns(@TempDir Path dir) {
        TaskStore store = new TaskStore(new StorageProperties(dir));
        Task created = store.save(task(null, new Schedule(ScheduleKind.DAILY, null, "02:30", null)));
        assertThat(created.id()).isNotBlank();

        for (int i = 0; i < TaskStore.KEPT_RUNS + 5; i++) {
            store.addRun(created.id(), new Run(Instant.ofEpochSecond(i), Instant.ofEpochSecond(i + 1), true, "run " + i));
        }
        Task reread = new TaskStore(new StorageProperties(dir)).get(created.id());
        assertThat(reread.runs()).hasSize(TaskStore.KEPT_RUNS);
        assertThat(reread.runs().getFirst().message()).isEqualTo("run 24");

        // editing keeps the history
        Task edited = store.save(new Task(created.id(), "renamed", Type.SQL, "c1", "select 1", null, null, null, false, null));
        assertThat(edited.runs()).hasSize(TaskStore.KEPT_RUNS);
        assertThat(edited.schedule().kind()).isEqualTo(ScheduleKind.MANUAL);

        store.delete(created.id());
        assertThat(store.all()).isEmpty();
    }

    @Test
    void validatesSchedules() {
        TaskService.validate(task(null, new Schedule(ScheduleKind.CRON, null, null, "0 30 2 * * MON-FRI")));
        assertThatThrownBy(() -> TaskService.validate(task(null, new Schedule(ScheduleKind.DAILY, null, "25:00", null))))
                .hasMessageContaining("HH:mm");
        assertThatThrownBy(() -> TaskService.validate(task(null, new Schedule(ScheduleKind.CRON, null, null, "every day"))))
                .hasMessageContaining("cron");
        assertThatThrownBy(() -> TaskService.validate(task(null, new Schedule(ScheduleKind.INTERVAL, 0, null, null))))
                .hasMessageContaining("minute");
        assertThatThrownBy(() -> TaskService.validate(new Task(null, "x", Type.EXPORT, "c1", "select 1", " ", null, null, true, null)))
                .hasMessageContaining("file");
    }

    @Test
    void writesCsvAndExpandsFileNames() throws Exception {
        QueryResult result = new QueryResult(List.of(new Column("id", "int4", "number"), new Column("name", "text", "text")),
                List.of(List.of(1, "a,b"), java.util.Arrays.asList(2, null)), false, null, 1);
        StringWriter out = new StringWriter();
        TaskService.writeDelimited(out, result, ',');
        assertThat(out.toString()).isEqualTo("id,name\r\n1,\"a,b\"\r\n2,\r\n");

        assertThat(TaskService.expandFile("/tmp/{name}-{date}.csv", "daily report")).matches("/tmp/daily_report-\\d{4}-\\d{2}-\\d{2}\\.csv");
    }
}
