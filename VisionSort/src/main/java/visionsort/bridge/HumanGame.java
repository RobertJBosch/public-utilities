/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks LLC
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEvent;
import forge.game.player.Player;
import forge.game.player.PlayerOutcome;
import forge.game.player.RegisteredPlayer;
import forge.player.PlayerControllerHuman;
import forge.util.MyRandom;

/**
 * A game against you: {"cmd":"play_human"} starts it and the game streams the play protocol
 * (visionsort/battle/play.py in VisionSort) while it runs, on its own thread:
 *
 *   {"cmd":"play_human", "id":1, "seed":7, "format":"Commander", "first":"<seat name>",
 *    "practice":{"redraws":true, "show_ai_hand":false},
 *    "stops":{"mine":["main1","main2"], "theirs":["blockers","end"], "ai_spells":true},
 *    "players":[{"name":"You", "deck":"X:/you.dck", "human":true},
 *               {"name":"AI", "deck":"X:/ai.dck", "profile":"Default", "settings":{...},
 *                "lookahead":"off", "mistakes":{...}}]}
 *   -> {"type":"started", "id":1, "game":"h1", "you":0, "seats":["You","AI"], "seed":7, "practice":{...}}
 *   then {"type":"update" | "thinking" | "decision" | "refused" | "ended" | "failed", "game":"h1", ...}
 *
 *   {"cmd":"answer", "game":"h1", "decision":17, ...}     the answer to decision 17
 *   {"cmd":"concede", "game":"h1"}                        you lose; "ended" follows
 *   {"cmd":"stops", "game":"h1", "mine":[...], ...}       where you get priority with an empty stack
 *   {"cmd":"end", "game":"h1"}                            leave: the game is abandoned, nothing follows
 *
 * Watching (bridge 0.9.0): {"cmd":"watch"} is the same request with only AI seats, "viewer" (the seat
 * whose side the table shows, default 0) and "show_hands" (default true). Nobody answers anything: the
 * game streams "started", an "update" at each of the AIs' decisions (whenever something happened), and
 * "ended" - a Test game played again for the table to step through. Only "end" applies to it.
 *
 * Walking back and resuming replay a game: the same request with "replay": the answers an earlier
 * game recorded (its "ended" answers, or VisionSort's own record of them, stops changes included),
 * "ids_from": the first decision id to use (so an old decision can't be answered by mistake) and
 * "walked_back": true for a walk back (it makes the game practice; resuming a save doesn't). The
 * replayed decisions aren't sent: when the answers run out, or one doesn't fit the game any more,
 * {"type":"resumed", "at": answers replayed, "exact": all fitted, "state", "log": the whole game
 * log so far} comes, then the game goes on live. Every decision carries "checksum", the whole
 * state's (StateSnapshot, libraries included), which is how a replay knows it's on track.
 *
 * The AI seats are set up exactly as in PlayCommand (skills, mistakes, look-ahead), seeded the
 * same way, so a game is a pure function of its seed, decks, settings and your answers: "ended"
 * carries the answers, for replaying it. There's no game time limit (you may think), and the AI's
 * own decision timer is lifted for the same reason as in PlayCommand.
 *
 * "update" comes before each of the AI's decisions and before each of yours, whenever something
 * happened: the state, Forge's game events since the last one and the new lines of the game log.
 */
final class HumanGame {
    private static final AtomicLong GAMES = new AtomicLong();
    /** The AI's decision timer, in seconds: never reached (see PlayCommand). */
    private static final int NO_AI_TIMER_S = 7 * 24 * 3600;

    private final String id = "h" + GAMES.incrementAndGet();
    private final Consumer<JsonObject> out;
    private final List<String> profiles = new ArrayList<>();
    private final AtomicInteger steps = new AtomicInteger();
    private final JsonArray events = new JsonArray();
    private final List<String> notes = new ArrayList<>();
    private int logSeen;
    private long seed;
    private Match match;
    private Game game;
    private Player human;                         // null when watching
    private Player viewer;                        // whose side the state shows (the human, or "viewer")
    private boolean watching;
    private boolean showAll;                      // watching: every hand shown
    private volatile HumanSeat seat;              // null when watching
    private volatile Player forced;
    private Thread thread;
    private volatile boolean over;
    private volatile boolean abandoned;
    private JsonObject practice;

    private HumanGame(final Consumer<JsonObject> out) {
        this.out = out;
    }

    /** Set the game up (decks loaded, seats made); start() then plays it. */
    static HumanGame create(final JsonObject req, final Consumer<JsonObject> out) throws Exception {
        return create(req, out, false);
    }

    /** watching: only AI seats, nobody answers (a Test game replayed for the table). */
    static HumanGame create(final JsonObject req, final Consumer<JsonObject> out, final boolean watching) throws Exception {
        final HumanGame g = new HumanGame(out);
        g.watching = watching;
        try {
            g.setUp(req);
        } catch (Exception | Error e) {
            g.profiles.forEach(Skills::unregister);
            throw e;
        }
        return g;
    }

    private void setUp(final JsonObject req) throws Exception {
        if (!req.has("seed")) {
            throw new IllegalArgumentException("A play_human request needs a \"seed\"");
        }
        seed = req.get("seed").getAsLong();
        final GameType type = GameType.valueOf(str(req, "format", "Commander"));
        practice = req.has("practice") ? req.getAsJsonObject("practice") : new JsonObject();
        final boolean showHands = practice.has("show_ai_hand") && practice.get("show_ai_hand").getAsBoolean();
        final boolean redraws = !practice.has("redraws") || practice.get("redraws").getAsBoolean();
        final String first = str(req, "first", null);
        final JsonArray seatsJson = req.has("players") ? req.getAsJsonArray("players") : new JsonArray();
        if (seatsJson.size() < 2) {
            throw new IllegalArgumentException("A game needs at least 2 players, not " + seatsJson.size());
        }
        int humans = 0;
        for (final JsonElement e : seatsJson) {
            if (e.getAsJsonObject().has("human") && e.getAsJsonObject().get("human").getAsBoolean()) {
                humans++;
            }
        }
        if (!watching && humans != 1) {
            throw new IllegalArgumentException("A play_human game seats exactly one human, not " + humans);
        }
        if (watching && humans != 0) {
            throw new IllegalArgumentException("A watched game has only AI seats");
        }
        showAll = !req.has("show_hands") || req.get("show_hands").getAsBoolean();

        final GameRules rules = new GameRules(type);
        rules.setAppliedVariants(EnumSet.of(type));
        final List<RegisteredPlayer> players = new ArrayList<>();
        final Set<String> names = new HashSet<>();
        final DecisionCounts counts = new DecisionCounts();
        final GameRecorder recorder = new GameRecorder(GameRecorder.Mode.NONE, -1);
        final String gameTag = "play" + id;

        GameIds.reset();                        // the same object numbers whatever ran before (GameIds)
        MyRandom.setRandom(new Random(seed));   // decks first, as PlayCommand: copies are spread over printings
        int i = 0;
        for (final JsonElement e : seatsJson) {
            final JsonObject p = e.getAsJsonObject();
            final File file = new File(p.get("deck").getAsString());
            if (!file.isFile()) {
                throw new IllegalArgumentException("No deck file at " + file);
            }
            final Deck deck = DeckSerializer.fromFile(file);
            final String name = str(p, "name", deck.getName());
            if (!names.add(name)) {
                throw new IllegalArgumentException("Two seats are both called " + name + "; seat names must differ");
            }
            final RegisteredPlayer rp = type == GameType.Commander
                    ? RegisteredPlayer.forCommander(deck) : new RegisteredPlayer(deck);
            if (p.has("human") && p.get("human").getAsBoolean()) {
                rp.setPlayer(new HumanController.Lobby(name, this));
            } else {
                final Skills.Lookahead lookahead = Skills.lookahead(str(p, "lookahead", "off"));
                final String profile = Skills.register(gameTag, name, str(p, "profile", ""),
                        p.has("settings") ? p.getAsJsonObject("settings") : null, lookahead);
                profiles.add(profile);
                final Policy policy = MistakePolicy.from(p.has("mistakes") ? p.getAsJsonObject("mistakes") : null, seed, i);
                final DecisionHook hook = new DecisionHook(name, policy, new JsonArray(), false, counts, recorder,
                        lookahead != Skills.Lookahead.OFF, first);
                hook.listen(this::aiDecides);
                rp.setPlayer(new BridgeLobbyPlayer(name, profile, hook));
            }
            players.add(rp);
            i++;
        }
        if (first != null && !names.contains(first)) {
            throw new IllegalArgumentException("\"first\" names " + first + ", but the seats are " + names);
        }

        MyRandom.setRandom(new Random(seed));   // again: the game itself starts from the seed alone
        match = new Match(rules, players, "VisionSort");
        game = match.createGame();
        game.AI_TIMEOUT = NO_AI_TIMER_S;
        game.AI_FLAT_MONTE_CARLO = false;       // set per seat around each of its decisions (DecisionHook)
        game.AI_FLAT_MONTE_CARLO_MAX = false;
        game.AI_FLAT_MONTE_CARLO_LITE = false;
        game.subscribeToEvents(this);
        for (final Player p : game.getRegisteredPlayers()) {
            if (p.getLobbyPlayer() instanceof HumanController.Lobby) {
                human = p;
            }
            if (p.getName().equals(first)) {
                forced = p;
            }
        }
        if (watching) {
            final int v = req.has("viewer") ? req.get("viewer").getAsInt() : 0;
            if (v < 0 || v >= game.getRegisteredPlayers().size()) {
                throw new IllegalArgumentException("\"viewer\" must be a seat, not " + v);
            }
            viewer = game.getRegisteredPlayers().get(v);
            return;
        }
        viewer = human;
        final HumanSeat s = new HumanSeat(this, game, human, this::send, showHands, redraws);
        s.setStops(req.has("stops") ? req.getAsJsonObject("stops") : defaultStops(), false);
        s.attach((PlayerControllerHuman) human.getController());
        final boolean walkedBack = req.has("walked_back") && req.get("walked_back").getAsBoolean();
        s.setReplay(req.has("replay") ? req.getAsJsonArray("replay") : new JsonArray(),
                req.has("ids_from") ? req.get("ids_from").getAsInt() : 1, walkedBack || req.has("replay"));
        if (walkedBack) {
            s.practiceUsed = true;                      // walking back is a practice option
        }
        seat = s;
    }

    private static JsonObject defaultStops() {
        final JsonObject s = new JsonObject();
        final JsonArray mine = new JsonArray();
        mine.add("main1");
        mine.add("main2");
        final JsonArray theirs = new JsonArray();
        theirs.add("blockers");
        theirs.add("end");
        s.add("mine", mine);
        s.add("theirs", theirs);
        s.addProperty("ai_spells", true);
        return s;
    }

    /** The reply to play_human: the protocol's "started". */
    JsonObject started(final JsonObject reply) {
        reply.addProperty("game", id);
        reply.addProperty("you", ViewState.seatOf(game.getRegisteredPlayers(), viewer));
        final JsonArray seats = new JsonArray();
        game.getRegisteredPlayers().forEach(p -> seats.add(p.getName()));
        reply.add("seats", seats);
        reply.addProperty("seed", seed);
        final JsonObject p = new JsonObject();
        p.addProperty("redraws", seat != null && seat.redraws);
        p.addProperty("show_ai_hand", seat != null ? seat.showHands : showAll);
        reply.add("practice", p);
        reply.addProperty("watching", watching);
        return reply;
    }

    void start() {
        thread = new Thread(this::run, "VisionSort play " + id);
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        Throwable failure = null;
        try {
            match.startGame(game);
        } catch (Throwable t) {
            failure = t;
        }
        over = true;
        if (seat != null) {
            seat.abort();
        }
        profiles.forEach(Skills::unregister);
        if (abandoned) {
            return;
        }
        if (failure != null && !game.isGameOver()) {
            failure.printStackTrace();
            final JsonObject m = new JsonObject();
            m.addProperty("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            send("failed", m);
            return;
        }
        if (seat != null) {
            seat.endReplayQuietly();                    // a replay that reached the game's end
        }
        flush();
        final GameOutcome outcome = game.getOutcome();
        final JsonObject m = new JsonObject();
        if (outcome == null || outcome.isDraw()) {
            m.add("winner", com.google.gson.JsonNull.INSTANCE);
        } else {
            int winner = -1;
            for (final Player p : game.getRegisteredPlayers()) {
                if (p.getOutcome() != null && p.getOutcome().hasWon()) {
                    winner = ViewState.seatOf(game.getRegisteredPlayers(), p);
                }
            }
            m.addProperty("winner", winner);
        }
        m.addProperty("reason", reason(outcome));
        m.addProperty("turns", outcome == null ? game.getPhaseHandler().getTurn() : outcome.getLastTurnNumber());
        m.addProperty("practice", seat != null && seat.practiceUsed);
        m.add("state", state());
        m.add("answers", seat != null ? seat.answers() : new JsonArray());
        send("ended", m);
    }

    /** Why the game ended. Two players: how the loser lost. More: who won, and how you went out if you did. */
    private String reason(final GameOutcome outcome) {
        if (outcome == null || outcome.isDraw()) {
            return "The game is a draw";
        }
        final List<Player> all = game.getRegisteredPlayers();
        if (all.size() > 2) {
            final Player winner = all.stream().filter(p -> p.getOutcome() != null && p.getOutcome().hasWon())
                    .findFirst().orElse(null);
            final String won = winner == null ? String.valueOf(outcome.getWinCondition())
                    : isYou(winner) ? "You won" : winner.getName() + " won";
            final PlayerOutcome mine = human == null ? null : human.getOutcome();
            return mine != null && !mine.hasWon() && mine.lossState != null
                    ? won + " (you were out: " + lost(human, mine).replaceFirst("^You ", "you ").replaceFirst("^Your ", "your ") + ")"
                    : won;
        }
        for (final Player p : all) {
            final PlayerOutcome o = p.getOutcome();
            if (o == null || o.hasWon() || o.lossState == null) {
                continue;
            }
            return lost(p, o);
        }
        for (final Player p : all) {
            final PlayerOutcome o = p.getOutcome();
            if (o != null && o.altWinSourceName != null) {
                return p.getName() + " won with " + o.altWinSourceName;
            }
        }
        return String.valueOf(outcome.getWinCondition());
    }

    private String lost(final Player p, final PlayerOutcome o) {
        {
            final boolean you = isYou(p);
            final String who = you ? "You" : p.getName();
            return switch (o.lossState) {
                case Conceded -> who + " conceded";
                case LifeReachedZero -> (you ? "Your" : p.getName() + "'s") + " life total reached 0";
                case Milled -> who + " drew from an empty library";
                case Poisoned -> who + (you ? " have" : " has") + " 10 poison counters";
                case CommanderDamage -> who + " took 21 damage from a commander";
                case SpellEffect -> who + " lost to " + (o.loseConditionSpell == null ? "a card's effect" : o.loseConditionSpell);
                default -> who + " lost (" + o.lossState + ")";
            };
        }
    }

    private boolean isYou(final Player p) {
        return human != null && p.getId() == human.getId();
    }

    // --- commands from VisionSort ---------------------------------------------------------------------------
    boolean over() {
        return over;
    }

    String id() {
        return id;
    }

    void command(final String cmd, final JsonObject req) {
        if (over) {
            return;
        }
        if (watching && !cmd.equals("end")) {
            return;                                     // nothing to answer in a watched game
        }
        switch (cmd) {
            case "answer" -> seat.answer(req);
            case "stops" -> seat.setStops(req, true);
            case "concede" -> {
                seat.abort();
                SwingUtilities.invokeLater(() -> ((PlayerControllerHuman) human.getController()).concede());
            }
            case "end" -> end();
            default -> throw new IllegalArgumentException("Unknown game command: " + cmd);
        }
    }

    /** Leave the game: it ends as abandoned, and its thread stops. */
    void end() {
        if (over) {
            return;
        }
        abandoned = true;
        game.setGameOver(GameEndReason.Draw);
        if (seat != null) {
            seat.abort();
            // releasing the inputs is enough: the game loop stops at the game over. Interrupting the thread made
            // Forge report a crash (InterruptedException in awaitInput) while it stopped.
            ((PlayerControllerHuman) human.getController()).getInputQueue().onGameOver(true);
        }
    }

    /** Wait for a game that was left to finish stopping (a walk back starts the next one right away). */
    boolean awaitStop(final long ms) throws InterruptedException {
        if (thread != null) {
            thread.join(ms);
        }
        return over;
    }

    boolean abandoned() {
        return abandoned;
    }

    // --- what the seats call ---------------------------------------------------------------------------------
    HumanSeat seat() {
        return seat;
    }

    Player forcedFirst() {
        return forced;
    }

    void step() {
        steps.incrementAndGet();
    }

    JsonObject state() {
        return ViewState.of(game, viewer, seat != null ? seat.showHands : showAll, steps.get());
    }

    synchronized void note(final String line) {
        if (line != null && !line.isEmpty()) {
            notes.add(line);
        }
    }

    /** An AI seat is about to decide (DecisionHook, on the game thread): show what happened, and that it's thinking. */
    private void aiDecides(final String seatName, final String decision) {
        if (over || (seat == null && !watching) || (seat != null && seat.quiet())) {
            return;
        }
        if (watching) {
            flush();                                    // a frame for each of the AIs' decisions that changed something
            steps.incrementAndGet();
            return;
        }
        flush();
        steps.incrementAndGet();
        final JsonObject m = new JsonObject();
        for (final Player p : game.getRegisteredPlayers()) {
            if (p.getName().equals(seatName)) {
                m.addProperty("seat", ViewState.seatOf(game.getRegisteredPlayers(), p));
            }
        }
        send("thinking", m);
    }

    @Subscribe
    public void onEvent(final GameEvent event) {
        final JsonObject e = GameRecorder.describe(event);
        if (e != null) {
            synchronized (this) {
                events.add(e);
            }
        }
    }

    /** Send an update if anything happened since the last message. */
    synchronized void flush() {
        if (seat != null && seat.quiet()) {
            while (events.size() > 0) {                  // a replay: "resumed" shows where it got to
                events.remove(0);
            }
            return;
        }
        final List<GameLogEntry> entries = new ArrayList<>(game.getGameLog().getLogEntries(null));   // newest first
        final JsonArray log = new JsonArray();
        final List<String> fresh = new ArrayList<>();
        for (int i = 0; i < entries.size() - logSeen; i++) {
            fresh.add(yours(entries.get(i).message()));
        }
        logSeen = entries.size();
        Collections.reverse(fresh);
        fresh.forEach(log::add);
        notes.forEach(log::add);
        notes.clear();
        if (events.size() == 0 && log.size() == 0) {
            return;
        }
        final JsonObject m = new JsonObject();
        m.add("state", state());
        m.add("events", events.deepCopy());
        m.add("log", log);
        while (events.size() > 0) {
            events.remove(0);
        }
        send("update", m);
    }

    /** A replay has reached the point to go on from: the state and the whole log so far. */
    synchronized void resumed(final int at, final boolean exact) {
        final List<GameLogEntry> entries = new ArrayList<>(game.getGameLog().getLogEntries(null));   // newest first
        logSeen = entries.size();
        Collections.reverse(entries);
        final JsonArray log = new JsonArray();
        entries.forEach(e -> log.add(yours(e.message())));
        notes.forEach(log::add);
        notes.clear();
        while (events.size() > 0) {
            events.remove(0);
        }
        final JsonObject m = new JsonObject();
        m.addProperty("at", at);
        m.addProperty("exact", exact);
        m.add("state", state());
        m.add("log", log);
        send("resumed", m);
    }

    /** Forge's log names your seat "You" in third-person sentences ("You has mulliganed down to 6 cards"). */
    static String yours(final String line) {
        if (line == null || !line.contains("You")) {
            return line;
        }
        return line.replaceAll("\\bYou has\\b", "You have").replaceAll("\\bYou is\\b", "You are")
                .replaceAll("\\bYou was\\b", "You were").replaceAll("\\bYou's\\b", "Your")
                .replaceAll("\\bYou searches\\b", "You search").replaceAll("\\bYou scries\\b", "You scry")
                .replaceAll("\\bYou (draw|play|cast|activate|discard|lose|gain|control|put|reveal|choose|attack|"
                        + "block|shuffle|sacrifice|return|exile|mill|keep|take|pay|create|tap|untap|surveil|"
                        + "mulligan)s\\b", "You $1");
    }

    private void send(final String type, final JsonObject fields) {
        final JsonObject m = new JsonObject();
        m.addProperty("type", type);
        m.addProperty("game", id);
        fields.entrySet().forEach(e -> m.add(e.getKey(), e.getValue()));
        out.accept(m);
    }

    private static String str(final JsonObject o, final String key, final String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }
}
