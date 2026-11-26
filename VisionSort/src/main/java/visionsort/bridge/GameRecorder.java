/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks LLC
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import java.lang.reflect.RecordComponent;
import java.util.Set;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.event.GameEvent;

/**
 * The game record: one step per real top-level decision of any seat (passes included), in order.
 *
 * Each step is taken when the decision is ASKED, before anyone answers it: walking back to step k
 * means returning to the moment seat X was asked decision k. A step holds the Forge events since
 * the previous step and the checksum of {@link StateSnapshot}; with mode SNAPSHOTS also the state.
 *
 * Replay: the same play request with "stop_at": k runs to step k and stops there (StopAtStep),
 * returning that step's state and every checksum up to it. Games are a pure function of seed, decks
 * and settings (see Main.repeatable()), so an AI-only game needs no decisions fed back; the
 * checksums prove each step matched. A human seat's answers (Play mode) are fed back by
 * play_human's "replay" (HumanSeat), checked against the same checksums.
 *
 * Events are Forge's own (forge.game.event, Java records), described generically from their record
 * components, so events a later Forge adds are recorded without code changes. Events that only
 * tell a GUI to redraw are skipped (SKIP).
 */
final class GameRecorder {
    enum Mode {
        NONE, EVENTS, STEPS, SNAPSHOTS;

        static Mode parse(final String s) {
            for (final Mode m : values()) {
                if (m.name().equalsIgnoreCase(s)) {
                    return m;
                }
            }
            throw new IllegalArgumentException("record must be none, events, steps or snapshots, not " + s);
        }
    }

    /** Redraw hints and duplicates: no information a record needs. "Zone" fires ~700 times a game to
     *  tell a GUI to redraw a zone; every actual move is a "CardChangeZone". */
    private static final Set<String> SKIP = Set.of("Zone", "CardStatsChanged", "PlayerStatsChanged", "CombatUpdate",
            "CombatChanged", "ManaPool", "PlayerPriority", "AddLog");

    /** Thrown on the game thread to stop at the requested step. An Error, so Forge's catch (Exception) blocks let it through. */
    static final class StopAtStep extends Error {
        StopAtStep() {
            super("stopped at the requested step", null, false, false);
        }
    }

    private final Mode mode;
    private final int stopAt;
    private final JsonArray steps = new JsonArray();
    private JsonArray pending = new JsonArray();
    private int next;
    private JsonObject stoppedState;

    GameRecorder(final Mode mode, final int stopAt) {
        this.mode = mode;
        this.stopAt = stopAt;
    }

    /** Called by DecisionHook when a real top-level decision is asked, before the AI answers. */
    void step(final Game game, final String seat, final String decision) {
        if (stoppedState != null) {
            // Forge caught the stop somewhere (e.g. on an AI evaluation thread) and played on:
            // record nothing more and try again at this decision
            throw new StopAtStep();
        }
        final int k = next++;
        if (mode == Mode.NONE && k != stopAt) {
            return;
        }
        // "events" leaves out the state and its checksum: what a Test run reads costs ~11% less game time
        final JsonObject state = mode == Mode.EVENTS && k != stopAt ? null : StateSnapshot.of(game);
        final JsonObject step = new JsonObject();
        step.addProperty("step", k);
        step.addProperty("seat", seat);
        step.addProperty("decision", decision);
        step.addProperty("turn", game.getPhaseHandler().getTurn());
        step.addProperty("phase", String.valueOf(game.getPhaseHandler().getPhase()));
        if (state != null) {
            step.addProperty("checksum", StateSnapshot.checksum(state));
        }
        step.add("events", pending);
        pending = new JsonArray();
        if (mode == Mode.SNAPSHOTS) {
            step.add("state", state);
        }
        steps.add(step);
        if (k == stopAt) {
            stoppedState = state;
            throw new StopAtStep();
        }
    }

    @Subscribe
    public void onEvent(final GameEvent event) {
        if (mode == Mode.NONE) {
            return;
        }
        final JsonObject e = describe(event);
        if (e != null) {
            pending.add(e);
        }
    }

    /** A Forge event described generically from its record components; null for the redraw hints in SKIP. */
    static JsonObject describe(final GameEvent event) {
        final Class<?> cls = event.getClass();
        final String name = cls.getSimpleName().replaceFirst("^GameEvent", "");
        if (SKIP.contains(name)) {
            return null;
        }
        final JsonObject e = new JsonObject();
        e.addProperty("event", name);
        if (cls.isRecord()) {
            for (final RecordComponent rc : cls.getRecordComponents()) {
                try {
                    e.add(rc.getName(), Describe.full(rc.getAccessor().invoke(event)));
                } catch (ReflectiveOperationException ex) {
                    e.addProperty(rc.getName(), "?");
                }
            }
        }
        return e;
    }

    boolean stopped() {
        return stoppedState != null;
    }

    /** {"steps":[...], "final":{"events":[...], "checksum":..., "state"?:...}}; or the stop's state. */
    void writeTo(final JsonObject reply, final Game game) {
        if (stopped()) {
            reply.addProperty("stopped_at", stopAt);
            reply.add("state", stoppedState);
            reply.add("steps", checksumsOnly());
            return;
        }
        if (stopAt >= 0) {
            // the game ended before reaching the step: say so, with the full record of what there was
            reply.addProperty("stop_not_reached", true);
        }
        if (mode == Mode.NONE) {
            return;
        }
        final JsonObject record = new JsonObject();
        record.add("steps", steps);
        final JsonObject last = new JsonObject();
        final JsonObject state = StateSnapshot.of(game);
        last.addProperty("checksum", StateSnapshot.checksum(state));
        last.add("events", pending);
        if (mode == Mode.SNAPSHOTS) {
            last.add("state", state);
        }
        record.add("final", last);
        record.addProperty("step_count", next);
        reply.add("record", record);
    }

    /** For a stop: steps 0..stop_at, without events, so the caller can compare checksums. */
    private JsonArray checksumsOnly() {
        final JsonArray out = new JsonArray();
        for (int i = 0; i < steps.size() && i <= stopAt; i++) {
            final JsonObject s = steps.get(i).getAsJsonObject();
            final JsonObject c = new JsonObject();
            c.add("step", s.get("step"));
            c.add("checksum", s.get("checksum"));
            out.add(c);
        }
        return out;
    }
}
