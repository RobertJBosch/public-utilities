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
 *   {"cmd":"resolve","names":[...]}   -> {"type":"resolved", "names":{name: Forge's name or null}}
 *                                        (see ResolveCommand)
 *   {"cmd":"profiles"}                -> {"type":"profiles", "settings":[...], "profiles":{...}}
 *                                        (every Forge AI setting with its explanation, and the
 *                                        values of Forge's profiles - see Skills.catalogue())
 *   {"cmd":"play", ...}               -> {"type":"result", ...}   (see {@link PlayCommand})
 *   {"cmd":"play_human", ...}         -> {"type":"started", ...}, then the game's messages until
 *                                        "ended" (see {@link HumanGame}); one such game at a time
 *   {"cmd":"answer" | "concede" | "stops" | "end", "game":...}   to the running play_human game
 *   {"cmd":"quit"}                    -> the process ends
 *
 * On start-up, before any request: {"type":"ready","forge":"2.0.16","startup_ms":...,"repeatable":true}.
 * A request that fails gets {"type":"error","message":...}; the bridge keeps serving. A reply with
 * "restart_required" (a game thread that wouldn't stop, or the JVM out of memory) is the last one:
 * the process exits after it, and VisionSort starts a new bridge.
 */
final class Protocol {
    private final BufferedReader in;
    private final PrintStream out;
    // serializeNulls: a decision that chose nothing (a pass, a declined option) must say "chosen": null,
    // not leave the field out - otherwise passes look like decisions that never finished
    private final Gson gson = new com.google.gson.GsonBuilder().serializeNulls().create();
    private volatile HumanGame human;      // the game against you, while it runs

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
                    case "resolve" -> send(ResolveCommand.run(req, message("resolved", req)));
                    case "profiles" -> {
                        final JsonObject m = message("profiles", req);
                        Skills.catalogue().entrySet().forEach(x -> m.add(x.getKey(), x.getValue()));
                        send(m);
                    }
                    case "play_human" -> {
                        if (human != null && !human.over()) {
                            send(error(req, "A game against you is already running: end it first"));
                        } else {
                            human = HumanGame.create(req, this::send);
                            send(human.started(message("started", req)));
                            human.start();
                        }
                    }
                    case "answer", "concede", "stops", "end" -> {
                        final HumanGame g = human;
                        if (g == null || g.over()) {
                            if (!cmd.equals("end")) {
                                send(error(req, "No game against you is running"));
                            }
                        } else if (req.has("game") && !req.get("game").getAsString().equals(g.id())) {
                            send(error(req, "That game is over"));
                        } else {
                            g.command(cmd, req);
                        }
                    }
                    case "play" -> {
                        if (human != null && !human.over()) {
                            send(error(req, "A game against you is running: Forge plays one game at a time"));
                            continue;
                        }
                        final JsonObject result = PlayCommand.run(req, message("result", req));
                        send(result);
                        if (result.has("restart_required")) {
                            return;   // a game thread wouldn't stop: end the process, VisionSort starts a new one
                        }
                    }
                    case "quit" -> {
                        if (human != null) {
                            human.end();
                        }
                        return;
                    }
                    default -> send(error(req, "Unknown command: " + cmd));
                }
            } catch (VirtualMachineError e) {
                // out of memory and the like: the process can't be trusted for another game
                e.printStackTrace();
                final JsonObject m = error(req, e.getClass().getSimpleName() + ": " + e.getMessage());
                m.addProperty("restart_required", true);
                send(m);
                return;
            } catch (Throwable e) {   // any other failure (assertion, linkage, a card script) fails this request only
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

    synchronized void send(final JsonObject m) {
        out.println(gson.toJson(m));
        out.flush();
    }
}
