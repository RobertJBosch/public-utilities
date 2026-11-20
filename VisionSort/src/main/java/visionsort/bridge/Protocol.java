/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * JSON lines in both directions. Every request has a "cmd" and may have an "id"; every reply echoes
 * the id, so VisionSort can match them up.
 *
 *   {"cmd":"ping"}                    -> {"type":"pong"}
 *   {"cmd":"play", ...}               -> {"type":"result", ...}   (see {@link PlayCommand})
 *   {"cmd":"quit"}                    -> the process ends
 *
 * On start-up, before any request: {"type":"ready","forge":"2.0.16","startup_ms":...,"repeatable":true}.
 * A request that fails gets {"type":"error","message":...}; the bridge keeps serving.
 */
final class Protocol {
    private final BufferedReader in;
    private final PrintStream out;
    // serializeNulls: a decision that chose nothing (a pass, a declined option) must say "chosen": null,
    // not leave the field out - otherwise passes look like decisions that never finished
    private final Gson gson = new com.google.gson.GsonBuilder().serializeNulls().create();

    Protocol(final BufferedReader in, final PrintStream out) {
        this.in = in;
        this.out = out;
    }

    void ready(final String forgeVersion, final long startupMs, final boolean repeatable) {
        final JsonObject m = message("ready", null);
        m.addProperty("forge", forgeVersion);
        m.addProperty("startup_ms", startupMs);
        m.addProperty("repeatable", repeatable);   // see Main.repeatable()
        send(m);
    }

    /** Answer requests until "quit" or the end of stdin (VisionSort closed the pipe). */
    void serve() throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject req = null;
            try {
                req = JsonParser.parseString(line).getAsJsonObject();
                final String cmd = req.has("cmd") ? req.get("cmd").getAsString() : "";
                switch (cmd) {
                    case "ping" -> send(message("pong", req));
                    case "play" -> send(PlayCommand.run(req, message("result", req)));
                    case "quit" -> { return; }
                    default -> send(error(req, "Unknown command: " + cmd));
                }
            } catch (Exception | StackOverflowError e) {
                e.printStackTrace();
                send(error(req, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
    }

    private JsonObject message(final String type, final JsonObject req) {
        final JsonObject m = new JsonObject();
        m.addProperty("type", type);
        if (req != null && req.has("id")) {
            m.add("id", req.get("id"));
        }
        return m;
    }

    private JsonObject error(final JsonObject req, final String text) {
        final JsonObject m = message("error", req);
        m.addProperty("message", text);
        return m;
    }

    private synchronized void send(final JsonObject m) {
        out.println(gson.toJson(m));
        out.flush();
    }
}
