/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.ai.AiProfileUtil;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.util.MyRandom;

/**
 * Plays one AI-vs-AI game:
 *
 *   {"cmd":"play", "id":1, "seed":101, "format":"Commander", "timeout_s":600,
 *    "lookahead":"off" | "on" | "max", "log":true, "decisions":true,
 *    "record":"none" | "steps" | "snapshots", "stop_at":k,
 *    "players":[{"name":"...", "deck":"X:/path/deck.dck", "profile":"Default",
 *                "mistakes":{"skip_spell":0.2, "choose":0.3, ...}}, ...]}
 *
 * -> {"type":"result", "id":1, "seed":101, "repeatable":true, "ms":5644, "draw":false,
 *     "winner":"...", "turns":8, "end_reason":"AllOpponentsLost", "deck_sizes":[100,100],
 *     "decision_counts":{"<seat>":{"<decision>":[made, changed], ...}, ...},
 *     "record":{...}, "log":[...], "decisions":[...]}
 *
 * "record" adds the game record and "stop_at" replays to a step (see GameRecorder). "decisions"
 * asks for the full decision log, and "mistakes" gives a seat Novice's mistake rates (see
 * DecisionHook, MistakePolicy). Without mistakes a seat plays exactly as Forge's own AI with that
 * profile.
 *
 * What makes a game a pure function of its seed, decks and settings ("repeatable" in the reply):
 *  - the random generator is seeded before the decks are loaded (a line like "8 Plains" spreads
 *    the copies over printings at random) and again right before the match, so neither earlier
 *    games nor loading shift the game's random numbers;
 *  - Forge's AI decides on a timer (game.AI_TIMEOUT, 5 s by default: when it runs out, the AI
 *    passes). It's set to the game's own timeout, so a busy PC can't change a decision;
 *  - the JVM runs with -XX:hashCode=2 (Main.repeatable());
 *  - look-ahead is off. Its search is limited by time, so with it on a game can differ on a busy PC
 *    (skills will cap it by playouts instead).
 *
 * The game runs on its own thread. If it outlives "timeout_s" it is ended as a draw and
 * interrupted; if its thread still won't stop, the reply says "restart_required" and the bridge
 * exits after sending it, rather than let a stray game thread disturb later games.
 *
 * "deck_sizes" counts what Forge actually loaded: Forge drops cards it can't find (it prints
 * "unsupported card"), so a 100-card deck that comes back as 99 lost a card.
 */
final class PlayCommand {
    private static final Set<String> LOOKAHEAD = Set.of("off", "on", "max");
    /** How long a game thread gets to stop after it's told the game is over. */
    private static final long STOP_GRACE_MS = 10_000;

    private PlayCommand() {}

    static JsonObject run(final JsonObject req, final JsonObject reply) throws Exception {
        if (!req.has("seed")) {
            throw new IllegalArgumentException("A play request needs a \"seed\"");
        }
        final long seed = req.get("seed").getAsLong();
        final GameType type = GameType.valueOf(str(req, "format", "Commander"));
        final String lookahead = str(req, "lookahead", "off");
        if (!LOOKAHEAD.contains(lookahead)) {
            throw new IllegalArgumentException("lookahead must be one of " + LOOKAHEAD + ", not " + lookahead);
        }
        final boolean withLog = req.has("log") && req.get("log").getAsBoolean();
        final int timeoutS = req.has("timeout_s") ? req.get("timeout_s").getAsInt() : 600;
        if (timeoutS <= 0) {
            throw new IllegalArgumentException("timeout_s must be positive, not " + timeoutS);
        }
        final int stopAt = req.has("stop_at") ? req.get("stop_at").getAsInt() : -1;
        if (req.has("stop_at") && stopAt < 0) {
            throw new IllegalArgumentException("stop_at must be 0 or more, not " + stopAt);
        }
        GameRecorder.Mode mode = GameRecorder.Mode.parse(str(req, "record", "none"));
        if (stopAt >= 0 && mode == GameRecorder.Mode.NONE) {
            mode = GameRecorder.Mode.STEPS;   // a stop returns the checksums of every step up to it
        }
        final JsonArray seats = req.has("players") ? req.getAsJsonArray("players") : new JsonArray();
        if (seats.size() < 2) {
            throw new IllegalArgumentException("A game needs at least 2 players, not " + seats.size());
        }

        final GameRules rules = new GameRules(type);
        rules.setAppliedVariants(EnumSet.of(type));
        rules.setSimTimeout(timeoutS);

        final List<RegisteredPlayer> players = new ArrayList<>();
        final JsonArray sizes = new JsonArray();
        final boolean keepDecisions = req.has("decisions") && req.get("decisions").getAsBoolean();
        final JsonArray decisions = new JsonArray();
        final DecisionCounts counts = new DecisionCounts();
        final GameRecorder recorder = new GameRecorder(mode, stopAt);
        final Set<String> names = new HashSet<>();

        MyRandom.setRandom(new Random(seed));   // loading decks uses it: copies are spread over printings
        int i = 0;
        for (final JsonElement e : seats) {
            final JsonObject p = e.getAsJsonObject();
            final File file = new File(p.get("deck").getAsString());
            if (!file.isFile()) {
                throw new IllegalArgumentException("No deck file at " + file);
            }
            final Deck deck = DeckSerializer.fromFile(file);
            sizes.add(deckSize(deck));
            final String profile = str(p, "profile", "");
            if (!profile.isEmpty() && (!AiProfileUtil.getProfilesDisplayList().contains(profile)
                    || profile.startsWith("Random"))) {
                // "Random (Every Game)" etc. are menu entries, not profiles: they'd silently play as defaults
                throw new IllegalArgumentException("Unknown AI profile " + profile + "; Forge has "
                        + AiProfileUtil.getProfilesDisplayList().stream().filter(x -> !x.startsWith("Random")).toList());
            }
            final String name = str(p, "name", deck.getName());
            if (!names.add(name)) {
                throw new IllegalArgumentException("Two seats are both called " + name + "; seat names must differ");
            }
            final RegisteredPlayer rp = type == GameType.Commander
                    ? RegisteredPlayer.forCommander(deck) : new RegisteredPlayer(deck);
            final Policy policy = MistakePolicy.from(p.has("mistakes") ? p.getAsJsonObject("mistakes") : null, seed, i);
            rp.setPlayer(new BridgeLobbyPlayer(name, profile,
                    new DecisionHook(name, policy, decisions, keepDecisions, counts, recorder)));
            players.add(rp);
            i++;
        }

        MyRandom.setRandom(new Random(seed));   // again: the game itself starts from the seed alone
        final Match match = new Match(rules, players, "VisionSort");
        final Game game = match.createGame();
        game.setNoGUIUser();
        game.AI_TIMEOUT = timeoutS;   // never let the AI's 5 s decision timer turn a slow decision into a pass
        game.AI_FLAT_MONTE_CARLO = !"off".equals(lookahead);
        game.AI_FLAT_MONTE_CARLO_MAX = "max".equals(lookahead);
        game.subscribeToEvents(recorder);   // before the game starts: shuffles and mulligans are events too

        final long t0 = System.nanoTime();
        final Throwable[] failure = new Throwable[1];
        final Thread thread = new Thread(() -> {
            try {
                match.startGame(game);
            } catch (Throwable t) {
                failure[0] = t;
            }
        }, "VisionSort game " + seed);
        thread.setDaemon(true);
        thread.start();
        thread.join(timeoutS * 1000L);
        final boolean timedOut = thread.isAlive();
        if (timedOut) {
            game.setGameOver(GameEndReason.Draw);
            thread.interrupt();   // an AI waiting on its own evaluation thread passes when interrupted
            thread.join(STOP_GRACE_MS);
        }
        reply.addProperty("seed", seed);
        reply.addProperty("repeatable", Main.repeatable() && "off".equals(lookahead));
        reply.addProperty("ms", (System.nanoTime() - t0) / 1_000_000);
        if (thread.isAlive()) {
            // the game can't be read safely while its thread still runs, and it would disturb later games
            reply.addProperty("end_reason", "Timeout");
            reply.addProperty("restart_required", true);
            return reply;
        }
        if (failure[0] != null && !(failure[0] instanceof GameRecorder.StopAtStep)) {
            if (failure[0] instanceof Exception ex) {
                throw ex;
            }
            throw (Error) failure[0];
        }
        if (!game.isGameOver()) {
            game.setGameOver(GameEndReason.Draw);   // stopped at a step: the game ends here
        }

        if (!recorder.stopped()) {
            final GameOutcome outcome = game.getOutcome();
            reply.addProperty("draw", outcome.isDraw());
            if (!outcome.isDraw() && outcome.getWinningLobbyPlayer() != null) {
                reply.addProperty("winner", outcome.getWinningLobbyPlayer().getName());
            }
            reply.addProperty("turns", outcome.getLastTurnNumber());
            reply.addProperty("end_reason", timedOut ? "Timeout" : String.valueOf(outcome.getWinCondition()));
        }
        recorder.writeTo(reply, game);
        reply.add("deck_sizes", sizes);
        reply.add("decision_counts", counts.toJson());
        if (keepDecisions) {
            reply.add("decisions", decisions);
        }
        if (withLog) {
            final List<GameLogEntry> entries = new ArrayList<>(game.getGameLog().getLogEntries(null));
            Collections.reverse(entries);   // Forge keeps newest first
            final JsonArray log = new JsonArray();
            for (final GameLogEntry entry : entries) {
                log.add(entry.toString());
            }
            reply.add("log", log);
        }
        return reply;
    }

    private static int deckSize(final Deck deck) {
        int n = deck.getMain().countAll();
        if (deck.has(DeckSection.Commander)) {
            n += deck.get(DeckSection.Commander).countAll();
        }
        return n;
    }

    private static String str(final JsonObject o, final String key, final String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }
}
