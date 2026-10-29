package io.justinbeaver.api;

import java.time.Instant;
import java.util.List;

import io.justinbeaver.task.TaskService;
import io.justinbeaver.task.TaskStore.Task;
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

/** Saved and scheduled tasks (the Task Manager). */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    /** A task as the UI sees it: with whether it is running and when it runs next. */
    public record TaskView(Task task, boolean running, Instant nextRun) {
    }

    private final TaskService tasks;

    public TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    public List<TaskView> list() {
        return tasks.all().stream().map(this::view).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskView create(@RequestBody Task task) {
        return view(tasks.save(new Task(null, task.name(), task.type(), task.connectionId(), task.sql(), task.file(), task.format(),
                task.schedule(), task.enabled(), null)));
    }

    @PutMapping("/{id}")
    public TaskView update(@PathVariable String id, @RequestBody Task task) {
        return view(tasks.save(new Task(id, task.name(), task.type(), task.connectionId(), task.sql(), task.file(), task.format(),
                task.schedule(), task.enabled(), null)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        tasks.delete(id);
    }

    @PostMapping("/{id}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void run(@PathVariable String id) {
        tasks.runNow(id);
    }

    private TaskView view(Task task) {
        return new TaskView(task, tasks.isRunning(task.id()), tasks.nextRun(task));
    }
}
