/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.phase.PhaseHandler;

/**
 * Every decision of one AI seat passes through here (see the generated HookedControllerAi):
 * Forge's AI decides, the seat's {@link Policy} may change the answer, and the decision is logged.
 *
 * Not logged or changed:
 *  - decisions inside the AI's look-ahead (Game.isSimulationCopy()): the AI imagining a playout
 *    isn't a move, and Novice shouldn't make mistakes in its imagination;
 *  - notifications (reveal..., notify..., waiting for input): nothing is chosen.
 *
 * Forge's AI sometimes asks itself further questions while deciding (choosing targets while
 * choosing a spell). Those nested decisions are logged with their depth, after their parent.
 * Only top-level decisions are handed to the policy, so one mistake isn't stacked on another
 * inside the same choice.
 */
public final class DecisionHook {
    private final String seat;
    private final Policy policy;
    private final JsonArray log;          // shared by every seat of the game, in order
    private final boolean keepLog;
    private final DecisionCounts counts;
    private int depth;

    DecisionHook(final String seat, final Policy policy, final JsonArray log, final boolean keepLog,
            final DecisionCounts counts) {
        this.seat = seat;
        this.policy = policy;
        this.log = log;
        this.keepLog = keepLog;
        this.counts = counts;
    }

    Object call(final HookedControllerAi controller, final String decision, final Object[] args,
            final Supplier<Object> forge) {
        final Game game = controller.getGame();
        if (game == null || game.isSimulationCopy() || isNotification(decision)) {
            return forge.get();
        }
        final JsonObject entry = new JsonObject();
        if (keepLog) {
            entry.addProperty("seq", log.size());
            log.add(entry);   // added now, so a parent comes before the decisions nested in it
        }
        final boolean top = depth == 0;
        depth++;
        final Object aiChoice;
        try {
            aiChoice = forge.get();
        } finally {
            depth--;
        }
        final Object chosen = top ? policy.adjust(decision, args, aiChoice) : aiChoice;
        final boolean changed = chosen != aiChoice;
        counts.count(seat, decision, changed);

        if (keepLog) {
            final PhaseHandler ph = game.getPhaseHandler();
            entry.addProperty("turn", ph.getTurn());
            entry.addProperty("phase", String.valueOf(ph.getPhase()));
            entry.addProperty("seat", seat);
            entry.addProperty("decision", decision);
            if (!top) {
                entry.addProperty("depth", depth + 1);
            }
            entry.add("args", Describe.args(args));
            // void decisions (attackers, blockers) record their effect: the combat after it
            entry.add("chosen", aiChoice == null && args.length > 0 && isVoidCombat(decision)
                    ? Describe.of(args[args.length - 1]) : Describe.of(chosen));
            if (changed) {
                entry.add("ai_wanted", Describe.of(aiChoice));
                entry.addProperty("changed_by", policy.name());
            }
        }
        return chosen;
    }

    private static boolean isVoidCombat(final String decision) {
        return decision.equals("declareAttackers") || decision.equals("declareBlockers");
    }

    private static boolean isNotification(final String decision) {
        return decision.startsWith("reveal") || decision.startsWith("notify")
                || decision.equals("autoPassCancel") || decision.equals("awaitNextInput")
                || decision.equals("cancelAwaitNextInput");
    }
}
