package io.justinbeaver.google;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.justinbeaver.connection.ConnectionStore.Synced;
import io.justinbeaver.script.ScriptStore;

/**
 * Everything that is synchronized, as one document, and the rule for merging two of them: per item the most recent
 * change wins, and a deletion wins over an older edit (and loses against a newer one).
 */
public record Snapshot(
        int version,
        List<Synced> connections,
        Map<String, Long> deletedConnections,
        List<ScriptStore.Synced> scripts,
        Map<String, Long> deletedScripts,
        Map<String, String> settings,
        long settingsUpdatedAt) {

    public Snapshot {
        connections = connections == null ? List.of() : connections;
        deletedConnections = deletedConnections == null ? Map.of() : deletedConnections;
        scripts = scripts == null ? List.of() : scripts;
        deletedScripts = deletedScripts == null ? Map.of() : deletedScripts;
        settings = settings == null ? Map.of() : settings;
    }

    /** The same content in a fixed order, so two equal snapshots serialize to the same text. */
    public Snapshot normalized() {
        List<Synced> c = new ArrayList<>(connections);
        c.sort(Comparator.comparing(s -> s.connection().id()));
        List<ScriptStore.Synced> s = new ArrayList<>(scripts);
        s.sort(Comparator.comparing(ScriptStore.Synced::name));
        return new Snapshot(version, c, new TreeMap<>(deletedConnections), s, new TreeMap<>(deletedScripts), new TreeMap<>(settings), settingsUpdatedAt);
    }

    public static Snapshot merge(Snapshot local, Snapshot remote) {
        Map<String, Synced> connections = new HashMap<>();
        Map<String, Long> deletedConnections = tombstones(local.deletedConnections, remote.deletedConnections);
        for (Synced c : concat(local.connections, remote.connections)) {
            connections.merge(c.connection().id(), c, (a, b) -> b.updatedAt() > a.updatedAt() ? b : a);
        }
        List<Synced> aliveConnections = new ArrayList<>();
        connections.forEach((id, c) -> {
            Long deletedAt = deletedConnections.get(id);
            if (deletedAt == null || c.updatedAt() > deletedAt) {
                aliveConnections.add(c);
                deletedConnections.remove(id);
            }
        });

        Map<String, ScriptStore.Synced> scripts = new HashMap<>();
        Map<String, Long> deletedScripts = tombstones(local.deletedScripts, remote.deletedScripts);
        for (ScriptStore.Synced s : concat(local.scripts, remote.scripts)) {
            scripts.merge(s.name(), s, (a, b) -> b.modified() > a.modified() ? b : a);
        }
        List<ScriptStore.Synced> aliveScripts = new ArrayList<>();
        scripts.forEach((name, s) -> {
            Long deletedAt = deletedScripts.get(name);
            if (deletedAt == null || s.modified() > deletedAt) {
                aliveScripts.add(s);
                deletedScripts.remove(name);
            }
        });

        boolean remoteSettingsNewer = remote.settingsUpdatedAt > local.settingsUpdatedAt;
        return new Snapshot(1, aliveConnections, deletedConnections, aliveScripts, deletedScripts,
                remoteSettingsNewer ? remote.settings : local.settings, Math.max(local.settingsUpdatedAt, remote.settingsUpdatedAt)).normalized();
    }

    /**
     * `base` with the chosen kinds of items from `top` laid over it, whatever their dates: what only `base` has stays,
     * and nothing is deleted. This is what an explicit export (top = this computer) or import (top = the cloud) does.
     */
    public static Snapshot overlay(Snapshot base, Snapshot top, boolean connections, boolean scripts, boolean settings) {
        Map<String, Synced> c = new HashMap<>();
        base.connections.forEach(x -> c.put(x.connection().id(), x));
        Map<String, Long> deletedConnections = new TreeMap<>(base.deletedConnections);
        if (connections) {
            for (Synced x : top.connections) {
                c.put(x.connection().id(), x);
                deletedConnections.remove(x.connection().id());
            }
        }
        Map<String, ScriptStore.Synced> s = new HashMap<>();
        base.scripts.forEach(x -> s.put(x.name(), x));
        Map<String, Long> deletedScripts = new TreeMap<>(base.deletedScripts);
        if (scripts) {
            for (ScriptStore.Synced x : top.scripts) {
                s.put(x.name(), x);
                deletedScripts.remove(x.name());
            }
        }
        return new Snapshot(1, new ArrayList<>(c.values()), deletedConnections, new ArrayList<>(s.values()), deletedScripts,
                settings ? top.settings : base.settings, settings ? top.settingsUpdatedAt : base.settingsUpdatedAt).normalized();
    }

    private static Map<String, Long> tombstones(Map<String, Long> a, Map<String, Long> b) {
        Map<String, Long> out = new TreeMap<>(a);
        b.forEach((k, v) -> out.merge(k, v, Math::max));
        return out;
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
