package io.justinbeaver.script;

public class ScriptNotFoundException extends RuntimeException {

    public ScriptNotFoundException(String name) {
        super("No script named \"" + name + "\"");
    }
}
