/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks LLC
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import java.util.List;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;

/**
 * Every decision of one AI seat passes through here (see the generated HookedControllerAi):
 * Forge's AI decides, the seat's {@link Policy} may change the answer, and the decision is logged.
 *
 * Not logged or changed:
 *  - decisions inside the AI's look-ahead (Game.isSimulationCopy()): the AI imagining a playout
 *    isn't a move, and Novice shouldn't make mistakes in its imagination;
 *  - notifications (reveal..., notify..., waiting for input): nothing is chosen.
 *
 * Nested decisions - asked while the seat is deciding something else - are logged with their
 * depth (1 = asked inside a top-level decision), after their parent. About 30% of decisions are
 * nested, mostly paying mana while casting. The policy sees them too, marked nested, except
 * inside chooseSpellAbilityToPlay: there the AI is only planning (and may be on its own
 * evaluation thread), so nothing it asks itself there is a move.
 *
 * Per seat, the hook also:
 *  - sets look-ahead: Forge's switch is one flag on the game, read only inside the deciding
 *    player's own AI, so it's set to this seat's value for each of its top-level decisions and
 *    put back afterwards (the opponent can be asked something in the middle);
 *  - forces who plays first, if asked: Forge flips for it, then asks the winner's
 *    chooseStartingPlayer; the hook lets the AI answer (so the game's random numbers stay the
 *    same) and then names the requested seat.
 */
public final class DecisionHook {
    private final String seat;
    private final Policy policy;
    private final JsonArray log;          // shared by every seat of the game, in order
    private final boolean keepLog;
    private final DecisionCounts counts;
    private final GameRecorder recorder;
    private final boolean lookahead;      // Forge's flat Monte Carlo search on for this seat
    private final String firstSeat;       // who plays first, or null for Forge's coin flip
    private int depth;
    private String topDecision = "";
    private TopListener listener;      // a play game: show the table that the AI is thinking

    /** Told when one of this seat's real top-level decisions starts, before the AI decides. */
    interface TopListener {
        void top(String seat, String decision);
    }

    void listen(final TopListener l) {
        listener = l;
    }

    private Peek peek = Peek.NONE;

    DecisionHook(final String seat, final Policy policy, final JsonArray log, final boolean keepLog,
            final DecisionCounts counts, final GameRecorder recorder, final boolean lookahead,
            final String firstSeat) {
        this.seat = seat;
        this.policy = policy;
        this.log = log;
        this.keepLog = keepLog;
        this.counts = counts;
        this.recorder = recorder;
        this.lookahead = lookahead;
        this.firstSeat = firstSeat;
    }

    /** What this seat may see beyond its own cards (VisionSort's Omniscient / Cheater skills). */
    DecisionHook peek(final Peek p) {
        this.peek = p;
        return this;
    }

    Object call(final HookedControllerAi controller, final String decision, final Object[] args,
            final Supplier<Object> forge) {
        final Game game = controller.getGame();
        if (game == null || game.isSimulationCopy() || isNotification(decision)) {
            return forge.get();
        }
        final int level = depth;   // 0 = top-level, 1 = asked while deciding something else, ...
        final boolean top = level == 0;
        if (top && listener != null) {
            listener.top(seat, decision);
        }
        if (top) {
            recorder.step(game, seat, decision);   // the state as the decision is asked; may stop the game here
            topDecision = decision;
        }
        final JsonObject entry = new JsonObject();
        if (keepLog) {
            final PhaseHandler ph = game.getPhaseHandler();
            entry.addProperty("seq", log.size());
            entry.addProperty("turn", ph.getTurn());       // when asked, not when answered
            entry.addProperty("phase", String.valueOf(ph.getPhase()));
            entry.addProperty("seat", seat);
            entry.addProperty("decision", decision);
            if (!top) {
                entry.addProperty("depth", level);
            }
            log.add(entry);   // added now, so a parent comes before the decisions nested in it
        }

        final boolean savedMc = game.AI_FLAT_MONTE_CARLO;
        final boolean savedMax = game.AI_FLAT_MONTE_CARLO_MAX;
        final boolean savedLite = game.AI_FLAT_MONTE_CARLO_LITE;
        if (top) {
            game.AI_FLAT_MONTE_CARLO = lookahead;
            game.AI_FLAT_MONTE_CARLO_MAX = false;    // Forge's max mode is timed; see Skills
            game.AI_FLAT_MONTE_CARLO_LITE = false;
        }
        final Player ai = controller.getPlayer();
        final List<Card> shown = top && peek.any() ? peek.show(game, ai) : List.of();
        depth++;
        final Object aiChoice;
        try {
            aiChoice = forge.get();
        } catch (RuntimeException | Error e) {
            if (keepLog) {
                entry.addProperty("interrupted", e.getClass().getSimpleName());   // a stop, or a Forge failure
            }
            throw e;
        } finally {
            depth--;
            Peek.hide(shown, ai);
            if (top) {
                game.AI_FLAT_MONTE_CARLO = savedMc;
                game.AI_FLAT_MONTE_CARLO_MAX = savedMax;
                game.AI_FLAT_MONTE_CARLO_LITE = savedLite;
            }
        }

        // what the AI wanted, described before a policy may change it in place (a combat, targets)
        final JsonElement wanted = keepLog ? describeChoice(decision, args, aiChoice) : null;
        Object chosen = aiChoice;
        String changedBy = null;
        if (top || !topDecision.equals("chooseSpellAbilityToPlay")) {
            final Object adjusted = policy.adjust(decision, args, aiChoice, !top);
            if (adjusted != aiChoice) {
                changedBy = policy.name();
                chosen = adjusted == Policy.CHANGED_IN_PLACE ? aiChoice : adjusted;
            }
        }
        if (top && firstSeat != null && decision.equals("chooseStartingPlayer")) {
            for (final Player p : game.getPlayers()) {
                if (p.getName().equals(firstSeat) && p != aiChoice) {
                    chosen = p;
                    changedBy = "first_seat";
                }
            }
        }
        // "changed" counts the policy's changes (Novice's mistakes); naming the first seat is setup, not a mistake
        counts.count(seat, decision, changedBy != null && !changedBy.equals("first_seat"));

        if (keepLog) {
            entry.add("args", Describe.args(args));
            entry.add("chosen", changedBy == null ? wanted : describeChoice(decision, args, chosen));
            if (changedBy != null) {
                entry.add("ai_wanted", wanted);
                entry.addProperty("changed_by", changedBy);
            }
        }
        return chosen;
    }

    /** Void decisions (attackers, blockers) record their effect: the combat after them. */
    private static JsonElement describeChoice(final String decision, final Object[] args, final Object choice) {
        if (choice == null && args.length > 0
                && (decision.equals("declareAttackers") || decision.equals("declareBlockers"))) {
            return Describe.of(args[args.length - 1]);
        }
        return Describe.of(choice);
    }

    private static boolean isNotification(final String decision) {
        return decision.startsWith("reveal") || decision.startsWith("notify")
                || decision.equals("autoPassCancel") || decision.equals("awaitNextInput")
                || decision.equals("cancelAwaitNextInput");
    }
}
