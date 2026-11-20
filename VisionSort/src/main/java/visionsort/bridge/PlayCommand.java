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
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;
import forge.view.TimeLimitedCodeBlock;

/**
 * Plays one AI-vs-AI game:
 *
 *   {"cmd":"play", "id":1, "seed":101, "format":"Commander", "timeout_s":600,
 *    "lookahead":"off" | "on" | "max", "log":true,
 *    "players":[{"name":"...", "deck":"X:/path/deck.dck", "profile":"Default"}, ...]}
 *
 * -> {"type":"result", "id":1, "seed":101, "draw":false, "winner":"...", "turns":8,
 *     "end_reason":"AllOpponentsLost", "ms":5644, "deck_sizes":[100,100], "log":["...", ...]}
 *
 * The random generator is re-seeded before every game, not once per process. Forge's sim mode
 * seeds once, so its second and later games depend on everything before them (M0: game 3 of a
 * 3-game run drifted). Re-seeding lets one warm process (~30 s to start) play game after game,
 * each one exactly repeatable from its seed alone.
 *
 * "deck_sizes" counts what Forge actually loaded: Forge drops cards it can't find (it prints
 * "unsupported card"), so a 100-card deck that comes back as 99 lost a card.
 */
final class PlayCommand {
    private PlayCommand() {}

    static JsonObject run(final JsonObject req, final JsonObject reply) throws Exception {
        final long seed = req.get("seed").getAsLong();
        final GameType type = GameType.valueOf(str(req, "format", "Commander"));
        final String lookahead = str(req, "lookahead", "off");
        final boolean withLog = req.has("log") && req.get("log").getAsBoolean();
        final int timeoutS = req.has("timeout_s") ? req.get("timeout_s").getAsInt() : 600;

        final GameRules rules = new GameRules(type);
        rules.setAppliedVariants(EnumSet.of(type));
        rules.setSimTimeout(timeoutS);

        final List<RegisteredPlayer> players = new ArrayList<>();
        final JsonArray sizes = new JsonArray();
        int i = 0;
        for (final JsonElement e : req.getAsJsonArray("players")) {
            final JsonObject p = e.getAsJsonObject();
            final File file = new File(p.get("deck").getAsString());
            if (!file.isFile()) {
                throw new IllegalArgumentException("No deck file at " + file);
            }
            final Deck deck = DeckSerializer.fromFile(file);
            sizes.add(deckSize(deck));
            final String profile = str(p, "profile", "");
            if (!profile.isEmpty() && !AiProfileUtil.getProfilesDisplayList().contains(profile)) {
                throw new IllegalArgumentException("Unknown AI profile " + profile + "; Forge has "
                        + AiProfileUtil.getProfilesDisplayList());
            }
            final RegisteredPlayer rp = type == GameType.Commander
                    ? RegisteredPlayer.forCommander(deck) : new RegisteredPlayer(deck);
            rp.setPlayer(GamePlayerUtil.createAiPlayer(str(p, "name", deck.getName()), i, profile));
            players.add(rp);
            i++;
        }

        // Seed only now, once the decks are loaded: a decklist without set codes makes Forge pick
        // printings at random while loading, which would otherwise shift every random number after it.
        MyRandom.setRandom(new Random(seed));
        final Match match = new Match(rules, players, "VisionSort");
        final Game game = match.createGame();
        game.setNoGUIUser();
        game.AI_FLAT_MONTE_CARLO = !"off".equals(lookahead);
        game.AI_FLAT_MONTE_CARLO_MAX = "max".equals(lookahead);

        final long t0 = System.nanoTime();
        boolean timedOut = false;
        try {
            TimeLimitedCodeBlock.runWithTimeout(() -> match.startGame(game), timeoutS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            timedOut = true;
        } finally {
            if (!game.isGameOver()) {
                game.setGameOver(GameEndReason.Draw);
            }
        }

        final GameOutcome outcome = game.getOutcome();
        reply.addProperty("seed", seed);
        reply.addProperty("draw", outcome.isDraw());
        if (!outcome.isDraw() && outcome.getWinningLobbyPlayer() != null) {
            reply.addProperty("winner", outcome.getWinningLobbyPlayer().getName());
        }
        reply.addProperty("turns", outcome.getLastTurnNumber());
        reply.addProperty("end_reason", timedOut ? "Timeout" : String.valueOf(outcome.getWinCondition()));
        reply.addProperty("ms", (System.nanoTime() - t0) / 1_000_000);
        reply.add("deck_sizes", sizes);
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
