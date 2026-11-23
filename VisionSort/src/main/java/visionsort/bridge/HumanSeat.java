/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks LLC
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import javax.swing.SwingUtilities;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.LobbyPlayer;
import forge.card.CardStateName;
import forge.card.MagicColor;
import forge.deck.CardPool;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameEntityView;
import forge.game.card.Card;
import forge.game.card.CardFaceView;
import forge.game.card.CardView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.SpellAbilityView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.AbstractGuiGame;
import forge.gamemodes.match.Selection;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputAttack;
import forge.gamemodes.match.input.InputBase;
import forge.gamemodes.match.input.InputBlock;
import forge.gamemodes.match.input.InputConfirm;
import forge.gamemodes.match.input.InputConfirmMulligan;
import forge.gamemodes.match.input.InputLockUI;
import forge.gamemodes.match.input.InputLondonMulligan;
import forge.gamemodes.match.input.InputPassPriority;
import forge.gamemodes.match.input.InputPayMana;
import forge.gamemodes.match.input.InputSelectTargets;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.localinstance.skin.FSkinProp;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableObject;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

/**
 * The human seat of a play game: Forge's own human player (PlayerControllerHuman) with a GUI that
 * turns everything Forge asks into the play protocol's decisions, and the answers back into what
 * a click on Forge's own table would have done. Forge keeps the rules: it only offers what's legal,
 * and checks what it's given.
 *
 * Forge asks a human player things two ways, and both become decisions:
 *  - Inputs (priority, paying mana, targets, attackers, blockers, confirmations, mulligans, picking
 *    cards): the game thread waits on an input until it's finished. When a new input becomes the
 *    current one, the decision for it is sent. An answer is applied on Swing's event thread - where
 *    Forge's GUI clicks run - through the input's own methods (selectCard, selectPlayer,
 *    selectAbility, OK, Cancel). If the input is still waiting afterwards, Forge didn't accept the
 *    answer ("refused", with Forge's reason where it gave one, and the same decision again) or
 *    accepted part of it (a land tapped towards a cost: a new decision for what's left).
 *  - Dialogs (getChoices, order, confirm, assignCombatDamage, getAbilityToPlay...): a method call
 *    that blocks until it returns the answer. These may come from the event thread itself (picking
 *    one of a land's mana abilities happens inside a click), so their answers are handed straight to
 *    the waiting call, never queued on the event thread.
 *
 * Practice redraws, who plays first and the stops are VisionSort's (HumanController, isUiSetToSkipPhase).
 */
final class HumanSeat {
    interface Sink {
        void send(String type, JsonObject fields);
    }

    /** What the open decision is about, to apply its answer. */
    private record Pending(int id, String kind, Input input, List<Object> options, JsonObject decision,
            CompletableFuture<JsonObject> reply, String checksum) {}

    private static final ZoneType[] ACTION_ZONES = {ZoneType.Hand, ZoneType.Battlefield, ZoneType.Command,
            ZoneType.Graveyard, ZoneType.Exile};
    private static final ZoneType[] MANA_ZONES = {ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard,
            ZoneType.Exile, ZoneType.Command};
    private static final JsonObject ABORTED = new JsonObject();
    /** How far a number with no upper limit goes (Forge's own list stops 99 past the minimum). */
    private static final int NUMBER_SPAN = 99;
    private static final Method IS_FINISHED;
    private static final Method GET_SELECTION;
    private static final Method IS_BUSY;
    private static final Field MANA_COST;
    private static final Field PAID_FOR;
    private static final Field TO_RETURN;
    private static final Field PHY_LIFE;
    /** A list of card names longer than this is a "name a card" question, not a list to pick from. */
    private static final int NAME_LIST_MAX = 60;
    private static final Field CONFIRM_CARD;
    private static final Field CONFIRM_SA;
    static {
        try {
            IS_FINISHED = InputBase.class.getDeclaredMethod("isFinished");
            IS_FINISHED.setAccessible(true);
            GET_SELECTION = InputBase.class.getDeclaredMethod("getSelection");
            GET_SELECTION.setAccessible(true);
            IS_BUSY = InputBase.class.getDeclaredMethod("isBusy");
            IS_BUSY.setAccessible(true);
            MANA_COST = InputPayMana.class.getDeclaredField("manaCost");
            MANA_COST.setAccessible(true);
            PAID_FOR = InputPayMana.class.getDeclaredField("saPaidFor");
            PAID_FOR.setAccessible(true);
            TO_RETURN = InputLondonMulligan.class.getDeclaredField("toReturn");
            TO_RETURN.setAccessible(true);
            PHY_LIFE = InputPayMana.class.getDeclaredField("phyLifeToLose");
            PHY_LIFE.setAccessible(true);
            CONFIRM_CARD = InputConfirm.class.getDeclaredField("card");
            CONFIRM_CARD.setAccessible(true);
            CONFIRM_SA = InputConfirm.class.getDeclaredField("sa");
            CONFIRM_SA.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);   // a Forge version whose inputs changed: the bridge can't seat a human
        }
    }

    private final Game game;
    private final Player me;
    private final List<Player> seats;
    private final Sink sink;
    private final HumanGame owner;
    final boolean showHands;
    final boolean redraws;
    private final Gui gui = new Gui();
    private PlayerControllerHuman controller;

    private volatile Set<PhaseType> stopsMine = EnumSet.noneOf(PhaseType.class);
    private volatile Set<PhaseType> stopsTheirs = EnumSet.noneOf(PhaseType.class);
    private volatile boolean aiSpells = true;

    private int nextId;
    private volatile Pending pending;
    private volatile boolean aborted;
    // event-thread state
    private Input shown;                  // the input the last decision was about
    private boolean applying;             // an answer is being applied: collect Forge's complaints
    private String complaint;
    private boolean awaitingAsync;        // Auto pay runs on a game thread: its result comes later
    private String prompt = "";
    private Input noMoreLife;             // a payment that didn't take another 2 life: don't offer it again
    private final String[] buttons = {"", ""};
    private final boolean[] enabled = {false, false};
    // mulligans and practice
    private int mulligans;
    private volatile boolean redrawRequested;
    volatile boolean practiceUsed;
    private final JsonArray answers = new JsonArray();
    // replaying a recorded game (walk back, resume): answers fed in silently until they run out
    private final java.util.ArrayDeque<JsonObject> replay = new java.util.ArrayDeque<>();
    private volatile boolean replaying;
    private volatile boolean resumePending;   // the replay is over: say so before the next live message
    private int replayed;
    private boolean exact = true;

    HumanSeat(final HumanGame owner, final Game game, final Player me, final Sink sink, final boolean showHands,
            final boolean redraws) {
        this.owner = owner;
        this.game = game;
        this.me = me;
        this.seats = game.getRegisteredPlayers();
        this.sink = sink;
        this.showHands = showHands;
        this.redraws = redraws;
        this.practiceUsed = showHands;
    }

    /** Seat Forge's human controller at this GUI (as HostedMatch.startGame does for its own). */
    void attach(final PlayerControllerHuman c) {
        controller = c;
        c.setGui(gui);
        gui.setGameView(null);
        gui.setGameView(game.getView());
        gui.setOriginalGameController(me.getView(), c);
        game.subscribeToEvents(c.getYieldController());
        // Forge's GUI conveniences that would make a game unrepeatable or slow, for this seat only: highlights and
        // suggestions come from timed scans that run the AI; the delays pace a screen we don't have
        final forge.gamemodes.match.YieldController y = c.getYieldController();
        y.setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, "false");
        y.setPref(FPref.UI_SHOW_AUTOTAP_PREVIEW, "false");
        y.setPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, "false");
        y.setPref(FPref.YIELD_DECLINE_SCOPE_STACK_YIELD, "NEVER");
        y.setPref(FPref.YIELD_DECLINE_SCOPE_NO_ACTIONS, "NEVER");
        y.setPref(FPref.YIELD_SKIP_PHASE_DELAY, "true");
        y.setPref(FPref.YIELD_SKIP_RESOLVE_DELAY, "true");
        // These two are read from the global preferences (in memory only; never saved). Forge's undo snapshots the
        // whole game at each of your priorities - seen: 570 s of CPU on one snapshot by turn 13 of a Commander game.
        // Walking back comes from VisionSort's replays instead.
        FModel.getPreferences().setPref(FPref.UI_UNDO_MODE, ForgeConstants.UNDO_MODE_OFF);
        FModel.getPreferences().setPref(FPref.UI_MANA_LOST_PROMPT, false);
        // with this on, scry goes through manipulateCardList (a drag-and-drop list); off, it asks which cards go
        // to the bottom and in what order (many / order), which the protocol has
        FModel.getPreferences().setPref(FPref.UI_SELECT_FROM_CARD_DISPLAYS, false);
        // A new input's decision is built when the input shows its prompt (Gui.showPromptMessage): only then
        // are its buttons and selection set. Watching the input queue instead races Forge's own prompt update.
    }

    int seat() {
        return seats.indexOf(me);
    }

    JsonArray answers() {
        return answers;
    }

    /** Where you get priority. record: a change made during the game, kept in the answers in order, since
     *  it changes which decisions come next (a replay applies it at the same point). */
    void setStops(final JsonObject s, final boolean record) {
        if (record) {
            final JsonObject r = new JsonObject();
            for (final String k : new String[] {"mine", "theirs", "ai_spells"}) {
                if (s.has(k)) {
                    r.add(k, s.get(k));
                }
            }
            r.addProperty("kind", "stops");
            synchronized (this) {
                answers.add(r);
            }
        }
        stopsMine = phases(s.has("mine") ? s.getAsJsonArray("mine") : null);
        stopsTheirs = phases(s.has("theirs") ? s.getAsJsonArray("theirs") : null);
        aiSpells = !s.has("ai_spells") || s.get("ai_spells").getAsBoolean();
    }

    private static Set<PhaseType> phases(final JsonArray names) {
        final Set<PhaseType> out = EnumSet.noneOf(PhaseType.class);
        if (names != null) {
            for (final JsonElement e : names) {
                final PhaseType p = ViewState.phaseType(e.getAsString());
                if (p == null) {
                    throw new IllegalArgumentException("unknown phase " + e.getAsString());
                }
                out.add(p);
            }
        }
        return out;
    }

    /** Replay these recorded answers (the "answers" an earlier game ended with, or VisionSort's own record
     *  of them): each is checked against the decision it answers - same kind, same state checksum - and
     *  given silently. When they run out, or one doesn't fit (the game went differently), "resumed" says
     *  how many were replayed and whether all fitted, and the game goes on live from there. */
    void setReplay(final JsonArray entries, final int firstId, final boolean announce) {
        nextId = Math.max(0, firstId - 1);
        for (final JsonElement e : entries) {
            replay.add(e.getAsJsonObject().deepCopy());
        }
        replaying = !replay.isEmpty();
        resumePending = !replaying && announce;        // a walk back to the first decision still says so
        drainStops();
    }

    /** Messages other than the replay's end are held back while replaying. */
    boolean quiet() {
        return replaying || resumePending;
    }

    void endReplayQuietly() {
        if (resumePending || replaying) {
            replay.clear();
            replaying = false;
            resumePending = false;
            owner.resumed(replayed, exact);
        }
    }

    private void drainStops() {
        while (!replay.isEmpty() && "stops".equals(str(replay.peekFirst(), "kind"))) {
            setStops(replay.pollFirst(), true);
        }
        if (replaying && replay.isEmpty()) {
            replaying = false;
            resumePending = true;
        }
    }

    private void drift() {
        exact = false;
        replay.clear();
        replaying = false;
        resumePending = true;
    }

    private static String str(final JsonObject o, final String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    boolean takeRedraw() {
        final boolean r = redrawRequested;
        redrawRequested = false;
        return r;
    }

    // --- answers in (the protocol's reader thread) -------------------------------------------------------
    void answer(final JsonObject a) {
        final Pending p = pending;
        if (p == null || !a.has("decision") || a.get("decision").getAsInt() != p.id()) {
            return;                                   // a decision that's no longer open: VisionSort's session ignores these too
        }
        if (p.reply() != null) {
            p.reply().complete(a);                    // a dialog: its own thread checks the answer
        } else {
            SwingUtilities.invokeLater(() -> apply(p, a));
        }
    }

    /** The game is ending: release any dialog that waits for an answer. Synchronized with send(): a
     * dialog being asked either sees `aborted` there or is pending here (it waited for good otherwise). */
    synchronized void abort() {
        aborted = true;
        final Pending p = pending;
        if (p != null && p.reply() != null) {
            p.reply().complete(ABORTED);
        }
    }

    // --- decisions out ------------------------------------------------------------------------------------
    private synchronized int send(final String kind, final String text, final JsonObject fields, final Input input,
            final List<Object> options, final CompletableFuture<JsonObject> reply, final int reuse) {
        final int id = reuse > 0 ? reuse : ++nextId;
        final JsonObject d = new JsonObject();
        d.addProperty("id", id);
        d.addProperty("kind", kind);
        d.addProperty("seat", seat());
        d.addProperty("prompt", text);
        fields.entrySet().forEach(e -> d.add(e.getKey(), e.getValue()));
        final String checksum = StateSnapshot.checksum(StateSnapshot.of(game));   // the whole state, libraries too
        d.addProperty("checksum", checksum);
        owner.step();
        if (replaying) {
            final JsonObject next = replay.peekFirst();
            final String want = next == null ? null : str(next, "checksum");
            if (next != null && kind.equals(str(next, "kind")) && (want == null || want.equals(checksum))) {
                replay.pollFirst();
                replayed++;
                final Pending p = new Pending(id, kind, input, options, d, reply, checksum);
                pending = p;
                final JsonObject a = next.deepCopy();
                a.addProperty("decision", id);
                drainStops();                          // a stops change made right after this answer
                if (reply != null) {
                    reply.complete(a);                  // a dialog: ask() takes it at once
                } else {
                    SwingUtilities.invokeLater(() -> apply(p, a));
                }
                return id;
            }
            drift();                                   // not the decision that was answered then: live from here
        }
        if (resumePending) {
            resumePending = false;
            owner.resumed(replayed, exact);
        }
        if (aborted) {                                // the game ended while this was being asked
            if (reply != null) {
                reply.complete(ABORTED);
            }
            return id;
        }
        owner.flush(false);                           // what happened first (the decision carries the state)
        final JsonObject m = new JsonObject();
        m.add("state", owner.state());
        m.add("decision", d);
        pending = new Pending(id, kind, input, options, d, reply, checksum);
        sink.send("decision", m);
        return id;
    }

    private void refuse(final int id, final String reason) {
        if (replaying) {
            drift();                                   // a recorded answer this game won't take: live from here
            return;
        }
        final JsonObject m = new JsonObject();
        m.addProperty("decision", id);
        m.addProperty("reason", reason);
        sink.send("refused", m);
    }

    private void record(final Pending p, final JsonObject a) {
        final JsonObject r = a.deepCopy();
        r.remove("cmd");
        r.remove("game");
        r.addProperty("kind", p.kind());
        r.addProperty("checksum", p.checksum());
        r.remove("decision");
        synchronized (this) {
            answers.add(r);
        }
    }

    /** Ask a dialog decision and wait for an answer `check` accepts (null = accepted). */
    private JsonObject ask(final String kind, final String text, final JsonObject fields,
            final Function<JsonObject, String> check) {
        if (aborted) {
            return null;
        }
        CompletableFuture<JsonObject> reply = new CompletableFuture<>();
        final int id = send(kind, clean(text), fields, null, null, reply, 0);
        while (true) {
            final JsonObject a;
            try {
                a = reply.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException e) {
                return null;
            }
            if (a == ABORTED) {
                return null;
            }
            final String problem = check.apply(a);
            if (problem == null) {
                record(pending, a);
                return a;
            }
            refuse(id, problem);
            reply = new CompletableFuture<>();
            send(kind, clean(text), fields, null, null, reply, id);
        }
    }

    // --- inputs: the event thread ---------------------------------------------------------------------------
    private Input top() {
        return controller == null ? null : controller.getInputQueue().getInput();
    }

    private static boolean finished(final Input in) {
        try {
            return in instanceof InputBase && (boolean) IS_FINISHED.invoke(in);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** A payment input is busy while a mana ability or Auto runs on a game thread (Forge's own lock). */
    private static boolean busy(final Input in) {
        try {
            return in instanceof InputBase && (boolean) IS_BUSY.invoke(in);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private void inputChanged() {
        final Input in = top();
        if (in == null || in instanceof InputLockUI || finished(in) || in == shown || aborted) {
            return;
        }
        awaitingAsync = false;
        deliver(in, 0);
    }

    private void deliver(final Input in, final int reuse) {
        try {
            build(in, reuse);
        } catch (RuntimeException e) {
            e.printStackTrace();                       // a decision we couldn't build: say so rather than hang
            owner.note("The bridge couldn't show this choice (" + e.getClass().getSimpleName() + "); Forge decides it");
            if (in instanceof InputPassPriority) {
                in.selectButtonOK();
            } else if (enabled[1]) {
                in.selectButtonCancel();
            } else {
                in.selectButtonOK();
            }
        }
    }

    private void build(final Input in, final int reuse) {
        shown = in;
        if (in instanceof InputPassPriority pp && autoPass()) {
            pp.selectButtonOK();                      // your own spell on the stack, or an AI spell you don't stop for
            return;
        }
        if (in instanceof InputPassPriority) {
            priority(in, reuse);
        } else if (in instanceof InputPayMana pm) {
            payMana(pm, reuse);
        } else if (in instanceof InputConfirmMulligan) {
            mulligan(in, reuse);
        } else if (in instanceof InputLondonMulligan) {
            bottom(in, reuse);
        } else if (in instanceof InputAttack) {
            attackers(in, reuse);
        } else if (in instanceof InputBlock) {
            blockers(in, reuse);
        } else if (in instanceof InputConfirm) {
            confirm(in, reuse);
        } else {
            final Selection sel = selection(in);
            if (sel != null && (!sel.cards().isEmpty() || !sel.targets().isEmpty())) {
                pick(in, sel, in instanceof InputSelectTargets ? "targets" : "choose", reuse);
            } else {
                confirm(in, reuse);                   // an input we don't know: its buttons
            }
        }
    }

    private boolean autoPass() {
        if (game.getStack().isEmpty()) {
            return false;
        }
        final Player who = game.getStack().peekAbility().getActivatingPlayer();
        return who == null || who.getId() == me.getId() || !aiSpells;
    }

    private void apply(final Pending p, final JsonObject a) {
        if (pending != p || p.input() != top() || finished(p.input())) {
            return;
        }
        applying = true;
        complaint = null;
        String problem;
        try {
            problem = applyTo(p, a);
        } catch (RuntimeException e) {
            e.printStackTrace();
            problem = "Forge couldn't do that (" + e.getClass().getSimpleName() + ")";
        } finally {
            applying = false;
        }
        if (problem == null) {
            record(p, a);
        }
        if (awaitingAsync) {
            return;
        }
        final Input in = p.input();
        if (top() == in && !finished(in)) {
            if (problem == null && complaint != null) {
                problem = complaint;
            }
            if (problem != null) {
                refuse(p.id(), problem);
                deliver(in, p.id());
            } else {
                deliver(in, 0);                       // accepted in part: what's left
            }
        }
    }

    private String applyTo(final Pending p, final JsonObject a) {
        final Input in = p.input();
        if (a.has("cancel")) {
            if (!enabled[1]) {
                return "This can't be cancelled";
            }
            in.selectButtonCancel();
            return null;
        }
        switch (p.kind()) {
            case "priority" -> {
                if (a.has("pass")) {
                    in.selectButtonOK();
                    return null;
                }
                final int i = a.get("play").getAsInt();
                if (i < 0 || i >= p.options().size()) {
                    return "There's no action " + i;
                }
                return in.selectAbility((SpellAbility) p.options().get(i)) ? null : "Forge won't play that now";
            }
            case "pay_mana" -> {
                if (a.has("auto")) {
                    if (!enabled[0]) {
                        return "Forge can't pay this for you";
                    }
                    in.selectButtonOK();
                    awaitingAsync = busy(in);          // paying on a game thread: its prompt says when it's done
                    return null;
                }
                if (a.has("life")) {
                    final int before = lifeToLose(in);
                    in.selectPlayer(me, null);         // Forge's own way: click your life total
                    if (lifeToLose(in) == before) {
                        noMoreLife = in;                // seen: offering it again looped
                        return "You can't pay life for this now";
                    }
                    return null;
                }
                if (a.has("pool")) {
                    ((InputPayMana) in).useManaFromPool(MagicColor.fromName(a.get("pool").getAsString()));
                    return null;
                }
                final Card c = game.findById(a.get("tap").getAsInt());
                if (c == null) {
                    return "That card can't pay for this";
                }
                final boolean taken = in.selectCard(c, null, null);
                // a mana ability resolves on a game thread, like Auto - unless none was chosen (a land with two
                // abilities asks which; seen: no pick left the seat waiting for a payment that never started)
                awaitingAsync = busy(in);
                return taken ? null : c.getName() + " can't pay for this";
            }
            case "mulligan" -> {
                if (a.has("redraw")) {
                    if (!redraws || mulligans > 0) {
                        return "A free redraw isn't offered now";
                    }
                    practiceUsed = true;
                    redrawRequested = true;
                    in.selectButtonOK();               // HumanController shuffles the hand back and draws again
                } else if (a.has("mulligan")) {
                    mulligans++;
                    in.selectButtonCancel();
                } else {
                    in.selectButtonOK();
                }
                return null;
            }
            case "confirm" -> {
                final int i = a.getAsJsonArray("picks").get(0).getAsInt();
                if (i < 0 || i >= p.options().size()) {
                    return "There's no option " + i;
                }
                if ("ok".equals(p.options().get(i))) {
                    in.selectButtonOK();
                } else {
                    in.selectButtonCancel();
                }
                return null;
            }
            case "targets", "choose" -> {
                return picks(p, in, a.getAsJsonArray("picks"));
            }
            case "attackers" -> {
                return attack(p, in, a.getAsJsonArray("attacks"));
            }
            case "blockers" -> {
                return block(in, a.getAsJsonArray("blocks"));
            }
            default -> {
                return "Can't answer a " + p.kind() + " decision";
            }
        }
    }

    private String picks(final Pending p, final Input in, final JsonArray picks) {
        for (final JsonElement e : picks) {
            if (finished(in)) {
                break;                                 // Forge had all it needed
            }
            final int i = e.getAsInt();
            if (i < 0 || i >= p.options().size()) {
                return "There's no option " + i;
            }
            final Object o = p.options().get(i);
            final boolean ok;
            if (o instanceof Card c) {
                ok = in.selectCard(c, null, null);
            } else if (o instanceof Player pl) {
                in.selectPlayer(pl, null);
                ok = true;
            } else if (o instanceof SpellAbilityStackInstance si) {
                ok = in.selectAbility(si.getSpellAbility());
            } else {
                ok = false;
            }
            if (!ok) {
                return complaint != null ? complaint : "Forge won't take " + label(o);
            }
        }
        if (!finished(in)) {
            if (!enabled[0]) {
                return complaint != null ? complaint : "That isn't enough: pick more";
            }
            in.selectButtonOK();
        }
        return null;
    }

    private String attack(final Pending p, final Input in, final JsonArray attacks) {
        final Combat combat = game.getCombat();
        for (final Card c : new ArrayList<>(combat.getAttackers())) {
            if (c.getController().getId() == me.getId()) {
                combat.removeFromCombat(c);              // start from no attackers: the answer is the whole attack
            }
        }
        for (final JsonElement e : attacks) {
            final JsonArray pair = e.getAsJsonArray();
            final Card attacker = game.findById(pair.get(0).getAsInt());
            final int d = pair.get(1).getAsInt();
            if (attacker == null || d < 0 || d >= p.options().size()) {
                return "That attack names something that isn't there";
            }
            final GameEntity defender = (GameEntity) p.options().get(d);
            if (defender instanceof Player pl) {
                in.selectPlayer(pl, null);
            } else {
                in.selectCard((Card) defender, null, null);
            }
            if (!in.selectCard(attacker, null, null) || !combat.isAttacking(attacker)) {
                return attacker.getName() + " can't attack " + label(defender);
            }
        }
        if (!CombatUtil.validateAttackers(combat)) {
            return "That attack isn't allowed: a creature must attack, or one can't attack that way";
        }
        in.selectButtonOK();
        return null;
    }

    private String block(final Input in, final JsonArray blocks) {
        final Combat combat = game.getCombat();
        for (final Card c : new ArrayList<>(combat.getAllBlockers())) {
            if (c.getController().getId() == me.getId()) {
                combat.removeFromCombat(c);              // start from no blocks
            }
        }
        for (final JsonElement e : blocks) {
            final JsonArray pair = e.getAsJsonArray();
            final Card blocker = game.findById(pair.get(0).getAsInt());
            final Card attacker = game.findById(pair.get(1).getAsInt());
            if (blocker == null || attacker == null || !combat.isAttacking(attacker)) {
                return "That block names something that isn't there";
            }
            in.selectCard(attacker, null, null);       // which attacker the next blocker blocks
            if (!in.selectCard(blocker, null, null) || !combat.isBlocking(blocker, attacker)) {
                return blocker.getName() + " can't block " + attacker.getName() + why(attacker, blocker);
            }
        }
        final String errors = CombatUtil.validateBlocks(combat, me);
        if (errors != null) {
            return clean(errors);
        }
        in.selectButtonOK();
        return null;
    }

    private static String why(final Card attacker, final Card blocker) {
        if (attacker.hasKeyword(Keyword.FLYING) && !blocker.hasKeyword(Keyword.FLYING) && !blocker.hasKeyword(Keyword.REACH)) {
            return ": it has flying";
        }
        if (blocker.isTapped()) {
            return ": it's tapped";
        }
        return "";
    }

    // --- building the decisions -------------------------------------------------------------------------------
    private void priority(final Input in, final int reuse) {
        final List<Object> sas = new ArrayList<>();
        final JsonArray actions = new JsonArray();
        for (final ZoneType z : ACTION_ZONES) {
            for (final Card c : me.getCardsIn(z)) {
                addActions(c, z, sas, actions);
            }
        }
        final forge.game.card.CardCollectionView library = me.getCardsIn(ZoneType.Library);
        if (!library.isEmpty()) {
            addActions(library.getFirst(), ZoneType.Library, sas, actions);   // "play from the top of your library"
        }
        final JsonObject f = new JsonObject();
        f.add("actions", actions);
        send("priority", priorityPrompt(), f, in, sas, null, reuse);
    }

    private void addActions(final Card c, final ZoneType z, final List<Object> sas, final JsonArray actions) {
        final List<SpellAbility> all = c.getAllPossibleAbilities(me, true);
        final long spells = all.stream().filter(SpellAbility::isSpell).count();
        for (final SpellAbility sa : all) {
            if (sa.isManaAbility()) {
                continue;                              // mana comes from paying a cost
            }
            final JsonObject a = new JsonObject();
            final String where = switch (z) {
                case Command -> " from the command zone";
                case Graveyard -> " from your graveyard";
                case Exile -> " from exile";
                case Library -> " from the top of your library";
                default -> "";
            };
            String label;
            String kind;
            if (sa.isLandAbility()) {
                label = "Play " + c.getName() + where;
                kind = "land";
            } else if (sa.isSpell()) {
                label = "Cast " + c.getName() + where + (spells > 1 ? " - " + clean(sa.toString()) : "");
                kind = z == ZoneType.Command ? "commander" : "spell";
            } else {
                label = c.getName() + ": " + clean(sa.toString());
                kind = "ability";
            }
            a.addProperty("label", label);
            a.addProperty("kind", kind);
            a.addProperty("card", c.getId());
            a.addProperty("cost", sa.getPayCosts() == null ? "" : sa.getPayCosts().toSimpleString());
            actions.add(a);
            sas.add(sa);
        }
    }

    private String priorityPrompt() {
        if (!game.getStack().isEmpty()) {
            return clean(game.getStack().peekAbility().getStackDescription()) + ": respond, or pass to let it resolve";
        }
        final PhaseHandler ph = game.getPhaseHandler();
        final String phase = ph.getPhase() == null ? "" : ph.getPhase().nameForUi;
        if (ph.isPlayerTurn(me)) {
            return "Your turn (" + phase + "): play a card, or pass";
        }
        return ph.getPlayerTurn().getName() + "'s turn (" + phase + "): play an instant or an ability, or pass";
    }

    private void payMana(final InputPayMana in, final int reuse) {
        final ManaCostBeingPaid cost;
        final SpellAbility paidFor;
        try {
            cost = (ManaCostBeingPaid) MANA_COST.get(in);
            paidFor = (SpellAbility) PAID_FOR.get(in);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        final String costText = braces(cost.toString(false, me.getManaPool()));
        final JsonArray sources = new JsonArray();
        for (final ZoneType z : MANA_ZONES) {
            for (final Card c : me.getCardsIn(z)) {
                if (InputPayMana.getAllManaAbilities(c, me).stream().anyMatch(SpellAbility::canPlay)) {
                    sources.add(c.getId());
                }
            }
        }
        final JsonArray pool = new JsonArray();
        final String[] colors = {"W", "U", "B", "R", "G", "C"};
        final byte[] bytes = {MagicColor.WHITE, MagicColor.BLUE, MagicColor.BLACK, MagicColor.RED, MagicColor.GREEN,
                MagicColor.COLORLESS};
        for (int i = 0; i < colors.length; i++) {
            if (me.getManaPool().getAmountOfColor(bytes[i]) > 0) {
                pool.add(colors[i]);
            }
        }
        final Card host = paidFor == null ? null : paidFor.getHostCard();
        final JsonObject f = new JsonObject();
        f.addProperty("cost", costText);
        if (host != null) {
            f.addProperty("card", host.getId());
        }
        f.add("sources", sources);
        f.add("pool", pool);
        f.addProperty("auto", enabled[0] && !buttons[0].isEmpty());
        f.addProperty("can_cancel", enabled[1]);
        final boolean lifeForMana = cost.containsPhyrexianMana()
                || (me.hasKeyword("PayLifeInsteadOf:B") && cost.hasAnyKind(forge.card.mana.ManaAtom.BLACK));
        // Forge adds up the life to pay (phyLifeToLose) and takes another 2 only if you can pay the total
        f.addProperty("can_pay_life", lifeForMana && in != noMoreLife
                && me.canPayLife(lifeToLose(in) + 2, false, paidFor));
        send("pay_mana", "Pay " + costText + (host == null ? "" : " for " + host.getName()), f, in, List.of(), null, reuse);
    }

    private static int lifeToLose(final Input in) {
        try {
            return in instanceof InputPayMana ? PHY_LIFE.getInt(in) : 0;
        } catch (IllegalAccessException e) {
            return 0;
        }
    }

    private void mulligan(final Input in, final int reuse) {
        final JsonArray hand = new JsonArray();
        me.getCardsIn(ZoneType.Hand).forEach(c -> hand.add(c.getId()));
        final JsonObject f = new JsonObject();
        f.add("hand", hand);
        f.addProperty("mulligans", mulligans);
        f.addProperty("redraw", redraws && mulligans == 0);
        send("mulligan", mulligans == 0 ? "Keep this hand?" : "Keep this hand? (mulligans: " + mulligans + ")",
                f, in, List.of(), null, reuse);
    }

    /** London mulligan: which cards go to the bottom. Forge asks right after the new hand is drawn, before
     *  keep / mulligan. Its input lists no selectable cards (Selection has only what's chosen), so the
     *  hand is offered here. Seen: the general fallback showed it as a confirm with only "Auto". */
    private void bottom(final Input in, final int reuse) {
        final int n;
        try {
            n = TO_RETURN.getInt(in);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        final List<Object> hand = new ArrayList<>(me.getCardsIn(ZoneType.Hand));
        final JsonArray opts = new JsonArray();
        hand.forEach(c -> opts.add(option(c)));
        final JsonObject f = new JsonObject();
        f.add("options", opts);
        f.addProperty("min", n);
        f.addProperty("max", n);
        f.addProperty("ordered", false);
        f.addProperty("can_cancel", false);
        send("choose", "Put " + n + (n == 1 ? " card" : " cards") + " on the bottom of your library", f, in, hand, null, reuse);
    }

    private void attackers(final Input in, final int reuse) {
        final Combat combat = game.getCombat();
        final JsonArray able = new JsonArray();
        for (final Card c : me.getCreaturesInPlay()) {
            if (CombatUtil.canAttack(c)) {
                able.add(c.getId());
            }
        }
        final List<Object> defenders = new ArrayList<>(combat.getDefenders());
        final JsonArray defs = new JsonArray();
        for (final Object d : defenders) {
            defs.add(option(d));
        }
        final JsonObject f = new JsonObject();
        f.add("attackers", able);
        f.add("defenders", defs);
        send("attackers", "Declare attackers (or none)", f, in, defenders, null, reuse);
    }

    private void blockers(final Input in, final int reuse) {
        final Combat combat = game.getCombat();
        final JsonArray attackers = new JsonArray();
        for (final Card a : combat.getAttackers()) {
            // with more players, only what attacks you (or your planeswalkers and battles) can be blocked by you
            final Player defending = combat.getDefenderPlayerByAttacker(a);
            if (defending == null || defending.getId() == me.getId()) {
                attackers.add(a.getId());
            }
        }
        final JsonArray able = new JsonArray();
        for (final Card c : me.getCreaturesInPlay()) {
            if (CombatUtil.canBlock(c, combat)) {
                able.add(c.getId());
            }
        }
        final JsonObject f = new JsonObject();
        f.add("attackers", attackers);
        f.add("blockers", able);
        send("blockers", "Declare blockers (or none)", f, in, List.of(), null, reuse);
    }

    private void confirm(final Input in, final int reuse) {
        final List<Object> which = new ArrayList<>();
        final JsonArray options = new JsonArray();
        if (enabled[0] && !buttons[0].isEmpty()) {
            options.add(buttons[0]);
            which.add("ok");
        }
        if (enabled[1] && !buttons[1].isEmpty()) {
            options.add(buttons[1]);
            which.add("cancel");
        }
        final JsonObject f = new JsonObject();
        f.add("options", options);
        final CardView about = confirmCard(in);
        if (about != null) {
            f.addProperty("card", about.getId());        // e.g. scry 1: "put X on the top or bottom?" shows X
            f.addProperty("card_name", about.getName());   // the card is usually in your library: not in the state
        }
        send("confirm", clean(prompt), f, in, which, null, reuse);
    }

    /** The card a confirmation is about: InputConfirm keeps it (or the ability whose card it is). */
    private static CardView confirmCard(final Input in) {
        if (!(in instanceof InputConfirm)) {
            return null;
        }
        try {
            final CardView card = (CardView) CONFIRM_CARD.get(in);
            if (card != null) {
                return card;
            }
            final SpellAbility sa = (SpellAbility) CONFIRM_SA.get(in);
            return sa == null || sa.getHostCard() == null ? null : sa.getHostCard().getView();
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private void pick(final Input in, final Selection sel, final String kind, final int reuse) {
        final List<Object> options = new ArrayList<>();
        for (final CardView cv : sel.cards()) {
            final Card c = game.findById(cv.getId());
            if (c != null) {
                options.add(c);
            }
        }
        for (final TrackableObject t : sel.targets()) {
            if (t instanceof PlayerView pv) {
                options.add(game.getPlayer(pv));
            } else if (t instanceof StackItemView sv) {
                for (final SpellAbilityStackInstance si : game.getStack()) {
                    if (si.getId() == sv.getId()) {
                        options.add(si);
                    }
                }
            }
        }
        final JsonArray opts = new JsonArray();
        options.forEach(o -> opts.add(option(o)));
        final JsonObject f = new JsonObject();
        f.add("options", opts);
        f.addProperty("min", Math.min(sel.min(), options.size()));
        f.addProperty("max", Math.min(Math.max(sel.max(), sel.min()), options.size()));
        f.addProperty("ordered", false);
        f.addProperty("can_cancel", enabled[1]);
        send(kind, clean(prompt), f, in, options, null, reuse);
    }

    private static Selection selection(final Input in) {
        try {
            return in instanceof InputBase ? (Selection) GET_SELECTION.invoke(in) : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    // --- labels -----------------------------------------------------------------------------------------------
    private JsonObject option(final Object o) {
        final JsonObject opt = new JsonObject();
        opt.addProperty("label", label(o));
        if (o instanceof Player || o instanceof Card) {
            opt.add("ref", ViewState.ref(seats, o));
        } else if (o instanceof CardView cv) {
            final JsonObject r = new JsonObject();
            r.addProperty("card", cv.getId());
            opt.add("ref", r);
        } else if (o instanceof PlayerView pv) {
            opt.add("ref", ViewState.ref(seats, game.getPlayer(pv)));
        }
        return opt;
    }

    private String label(final Object o) {
        if (o instanceof Player p) {
            return p.getId() == me.getId() ? p.getName() + " (you)" : p.getName();
        }
        if (o instanceof Card c) {
            final String zone = c.getZone() == null ? "" : switch (c.getZone().getZoneType()) {
                case Battlefield, Hand, Stack -> "";
                default -> ", " + ViewState.zone(c.getZone().getZoneType());
            };
            final String whose = c.getController() != null && c.getController().getId() != me.getId()
                    ? " (" + c.getController().getName() + zone + ")" : zone.isEmpty() ? "" : " (" + zone.substring(2) + ")";
            return c.getName() + whose;
        }
        if (o instanceof SpellAbilityStackInstance si) {
            return clean(si.getStackDescription());
        }
        if (o instanceof CardView cv) {
            return cv.getName();
        }
        if (o instanceof PlayerView pv) {
            return pv.getName();
        }
        if (o instanceof SpellAbilityView sv) {
            return clean(sv.getDescription());
        }
        return clean(String.valueOf(o));
    }

    private static String clean(final String s) {
        return s == null ? "" : s.replace("(Targeting ERROR)", "").replaceAll("\\s*\\n+\\s*", " ").trim();
    }

    private static String braces(final String cost) {
        final String c = cost.trim();
        if (c.contains("{")) {
            return c;
        }
        final StringBuilder sb = new StringBuilder();
        for (final String part : c.split("\\s+")) {
            if (!part.isEmpty()) {
                sb.append('{').append(part).append('}');
            }
        }
        return sb.length() == 0 ? "{0}" : sb.toString();
    }

    // --- the dialog decisions -----------------------------------------------------------------------------------
    private static String picksProblem(final JsonObject a, final int count, final int min, final int max) {
        if (!a.has("picks")) {
            return "Pick from the options";
        }
        final JsonArray picks = a.getAsJsonArray("picks");
        final Set<Integer> seen = new java.util.HashSet<>();
        for (final JsonElement e : picks) {
            final int i = e.getAsInt();
            if (i < 0 || i >= count || !seen.add(i)) {
                return "Pick different options from the list";
            }
        }
        if (picks.size() < min || picks.size() > max) {
            return min == max ? "Pick " + min : "Pick " + min + " to " + max;
        }
        return null;
    }

    private <T> List<T> choose(final String text, final int min, final int max, final List<T> choices,
            final Function<T, String> display, final boolean ordered) {
        return choose(text, min, max, choices, display, ordered, null);
    }

    /**
     * "Name a card" (Pithing Needle, Meddling Mage, Cranial Extraction...): Forge offers every card face it
     * knows that fits (~30,000), sorted - as a list of choices, far too long to send or to pick from. It's
     * a "name_card" decision instead, answered {"name": "..."}: the name is checked against Forge's own list
     * (any case; a split card by either face or "A // B"), so a name the card doesn't allow is refused.
     */
    private <T> T nameCard(final String text, final List<T> choices) {
        final Map<String, T> byName = new HashMap<>();
        for (final T c : choices) {
            final String n = ((CardFaceView) c).getName();
            byName.putIfAbsent(n.toLowerCase(Locale.ROOT), c);
        }
        final JsonObject f = new JsonObject();
        f.addProperty("allowed", choices.size());       // how many names it would take (one is asked)
        final JsonObject a = ask("name_card", text, f, x -> {
            if (!x.has("name") || x.get("name").getAsString().isBlank()) {
                return "Name a card";
            }
            final String n = x.get("name").getAsString().trim();
            return byName.containsKey(n.toLowerCase(Locale.ROOT)) ? null : n + " can't be named here";
        });
        if (a == null) {
            return choices.get(0);                      // the game ended: Forge's default
        }
        return byName.get(a.get("name").getAsString().trim().toLowerCase(Locale.ROOT));
    }

    private <T> List<T> choose(final String text, final int min, final int max, final List<T> choices,
            final Function<T, String> display, final boolean ordered, final CardView card) {
        if (choices.isEmpty()) {
            return new ArrayList<>();
        }
        final JsonArray opts = new JsonArray();
        for (final T c : choices) {
            final JsonObject o = option(c);
            if (display != null) {
                o.addProperty("label", clean(display.apply(c)));
            }
            opts.add(o);
        }
        final JsonObject f = new JsonObject();
        f.add("options", opts);
        f.addProperty("min", min);
        f.addProperty("max", max);
        f.addProperty("ordered", ordered);
        if (card != null) {
            f.addProperty("card", card.getId());           // what the choice is for (order's reference card)
            f.addProperty("card_name", card.getName());
        }
        final JsonObject a = ask("choose", text, f, x -> picksProblem(x, choices.size(), min, max));
        final List<T> out = new ArrayList<>();
        if (a == null) {                                // the game ended: Forge's default, the first ones
            for (int i = 0; i < min; i++) {
                out.add(choices.get(i));
            }
            return out;
        }
        a.getAsJsonArray("picks").forEach(e -> out.add(choices.get(e.getAsInt())));
        return out;
    }

    private int buttonsDialog(final String text, final List<String> labels) {
        return buttonsDialog(text, labels, null);
    }

    /** card: the card the question is about, when Forge names one (the table shows it with the buttons). */
    private int buttonsDialog(final String text, final List<String> labels, final CardView card) {
        final JsonArray opts = new JsonArray();
        labels.forEach(opts::add);
        final JsonObject f = new JsonObject();
        f.add("options", opts);
        if (card != null) {
            f.addProperty("card", card.getId());
            f.addProperty("card_name", card.getName());
        }
        final JsonObject a = ask("confirm", text, f, x -> picksProblem(x, labels.size(), 1, 1));
        return a == null ? 0 : a.getAsJsonArray("picks").get(0).getAsInt();
    }

    private Map<CardView, Integer> damage(final CardView attacker, final List<CardView> blockers, final int amount,
            final GameEntityView defender) {
        final boolean trample = defender != null;
        final JsonObject f = new JsonObject();
        f.addProperty("card", attacker.getId());
        final JsonArray ids = new JsonArray();
        blockers.forEach(b -> ids.add(b.getId()));
        f.add("blockers", ids);
        f.addProperty("amount", amount);
        f.addProperty("trample", trample);
        final JsonObject a = ask("damage", "Divide " + nameOf(attacker) + "'s " + amount + " damage", f, x -> {
            if (x.has("auto")) {
                return null;
            }
            if (!x.has("assign")) {
                return "Assign the damage, or let Forge do it";
            }
            int sum = x.has("to_defender") ? x.get("to_defender").getAsInt() : 0;
            if (sum < 0) {
                return "Assign no less than 0 damage";
            }
            if (sum > 0 && !trample) {
                return "Only an attacker with trample assigns damage to the player";
            }
            final Set<Integer> seen = new java.util.HashSet<>();
            for (final JsonElement e : x.getAsJsonArray("assign")) {
                final int id = e.getAsJsonArray().get(0).getAsInt();
                final int n = e.getAsJsonArray().get(1).getAsInt();
                if (blockers.stream().noneMatch(b -> b.getId() == id) || !seen.add(id)) {
                    return "Assign damage to each blocker once";
                }
                if (n < 0) {
                    return "Assign no less than 0 damage";
                }
                sum += n;
            }
            return sum == amount ? null : "Assign all " + amount + " damage";
        });
        final Map<CardView, Integer> out = new HashMap<>();
        if (a == null || a.has("auto")) {
            int left = amount;
            final Card source = game.findById(attacker.getId());
            final boolean deathtouch = source != null && source.hasKeyword(Keyword.DEATHTOUCH);
            for (int i = 0; i < blockers.size(); i++) {
                final Card b = game.findById(blockers.get(i).getId());
                final int lethal = b == null ? 0 : deathtouch ? 1 : Math.max(0, b.getNetToughness() - b.getDamage());
                final int n = (i == blockers.size() - 1 && !trample) ? left : Math.min(left, lethal);
                out.put(blockers.get(i), n);
                left -= n;
            }
            if (left > 0) {
                out.put(null, left);                     // trample: the rest to the player
            }
            return out;
        }
        for (final JsonElement e : a.getAsJsonArray("assign")) {
            final int id = e.getAsJsonArray().get(0).getAsInt();
            blockers.stream().filter(b -> b.getId() == id).findFirst()
                    .ifPresent(b -> out.put(b, e.getAsJsonArray().get(1).getAsInt()));
        }
        if (a.has("to_defender") && a.get("to_defender").getAsInt() > 0) {
            out.put(null, a.get("to_defender").getAsInt());
        }
        return out;
    }

    /** A card's name as the state names it (ViewState): the view's can be blank (seen: "Divide 's 2 damage"). */
    private String nameOf(final CardView v) {
        final Card c = game.findById(v.getId());
        final String n = c == null ? v.getName()
                : c.isFaceDown() ? c.getState(CardStateName.Original).getName() : c.getName();
        return n == null || n.isBlank() ? "the attacker" : n;
    }

    /** A number in a range (X, how many): Forge's GUI offers it as a list of numbers plus "Other". */
    private Integer number(final String text, final int min, final int max) {
        if (max <= min) {
            return min;
        }
        final int hi = max == Integer.MAX_VALUE ? min + NUMBER_SPAN : max;   // "any number": a range a person can use
        final JsonObject f = new JsonObject();
        f.addProperty("min", min);
        f.addProperty("max", hi);
        f.addProperty("can_cancel", true);
        final JsonObject a = ask("number", text, f, x -> {
            if (x.has("cancel")) {
                return null;
            }
            if (!x.has("number")) {
                return "Pick a number";
            }
            final int n = x.get("number").getAsInt();
            return n < min || n > hi ? "Pick a number from " + min + " to " + hi : null;
        });
        return a == null || a.has("cancel") ? null : a.get("number").getAsInt();
    }

    /** Dividing an amount among targets (damage, counters, shields): the "divide" decision. "auto": as even as it
     *  goes, the first targets getting what's left over. */
    private Map<Object, Integer> divide(final CardView source, final Map<Object, Integer> target, final int amount,
            final boolean atLeastOne, final String label) {
        final List<Object> keys = new ArrayList<>(target.keySet());
        final int floor = atLeastOne ? 1 : 0;
        final JsonArray opts = new JsonArray();
        keys.forEach(k -> opts.add(option(k)));
        final JsonObject f = new JsonObject();
        if (source != null) {
            f.addProperty("card", source.getId());
        }
        f.add("options", opts);
        f.addProperty("amount", amount);
        f.addProperty("at_least_one", atLeastOne);
        final String what = label == null || label.isBlank() ? "" : " " + label;
        final JsonObject a = keys.size() <= 1 ? null : ask("divide",
                "Divide " + amount + what + (source == null ? "" : " from " + source.getName()), f, x -> {
                    if (x.has("auto")) {
                        return null;
                    }
                    if (!x.has("divide")) {
                        return "Divide the amount";
                    }
                    int sum = 0;
                    final Set<Integer> seen = new java.util.HashSet<>();
                    for (final JsonElement e : x.getAsJsonArray("divide")) {
                        final int i = e.getAsJsonArray().get(0).getAsInt();
                        final int n = e.getAsJsonArray().get(1).getAsInt();
                        if (i < 0 || i >= keys.size() || !seen.add(i) || n < floor) {
                            return atLeastOne ? "Give each target at least 1" : "Divide it among the targets";
                        }
                        sum += n;
                    }
                    if (atLeastOne && seen.size() < keys.size()) {
                        return "Give each target at least 1";
                    }
                    return sum == amount ? null : "Divide all " + amount;
                });
        final Map<Object, Integer> out = new java.util.LinkedHashMap<>();
        if (a == null || a.has("auto")) {
            int left = amount;
            for (int i = 0; i < keys.size(); i++) {
                final int even = amount / keys.size() + (i < amount % keys.size() ? 1 : 0);
                final int n = Math.min(left, Math.max(floor, even));
                out.put(keys.get(i), n);
                left -= n;
            }
            return out;
        }
        keys.forEach(k -> out.put(k, 0));
        for (final JsonElement e : a.getAsJsonArray("divide")) {
            out.put(keys.get(e.getAsJsonArray().get(0).getAsInt()), e.getAsJsonArray().get(1).getAsInt());
        }
        return out;
    }

    /** Forge's GUI interface, as the play protocol. */
    private final class Gui extends AbstractGuiGame {
        @Override
        protected void updateCurrentPlayer(final PlayerView player) {
        }

        @Override
        public void openView(final TrackableCollection<PlayerView> myPlayers) {
        }

        @Override
        public void showCombat() {
        }

        @Override
        public void showPromptMessage(final PlayerView playerView, final String message, final CardView card) {
            prompt = message == null ? "" : message;
            if (awaitingAsync && !applying && shown != null && top() == shown && !finished(shown)) {
                awaitingAsync = false;                  // a payment made on a game thread, with more to pay: ask again
                final Input in = shown;
                SwingUtilities.invokeLater(() -> deliver(in, 0));
            } else if (!applying) {
                SwingUtilities.invokeLater(HumanSeat.this::inputChanged);   // after the input has set its buttons too
            }
        }

        @Override
        public void updateButtons(final PlayerView owner, final String label1, final String label2,
                final boolean enable1, final boolean enable2, final boolean focus1) {
            buttons[0] = label1 == null ? "" : label1;
            buttons[1] = label2 == null ? "" : label2;
            enabled[0] = enable1;
            enabled[1] = enable2;
        }

        @Override
        public void flashIncorrectAction() {
            if (applying && complaint == null) {
                complaint = "Forge won't take that";
            }
        }

        @Override
        public void alertUser() {
        }

        @Override
        public void finishGame() {
        }

        @Override
        public void setPanelSelection(final CardView hostCard) {
        }

        @Override
        public void setCard(final CardView card) {
        }

        @Override
        public void setPlayerAvatar(final LobbyPlayer player, final IHasIcon ihi) {
        }

        @Override
        public boolean isUiSetToSkipPhase(final PlayerView playerTurn, final PhaseType phase) {
            final Player turn = game.getPlayer(playerTurn);
            final boolean mine = turn != null && turn.getId() == me.getId();
            return !(mine ? stopsMine : stopsTheirs).contains(phase);
        }

        @Override
        public void message(final String message, final String title) {
            if (applying) {
                complaint = clean(message);
            } else {
                owner.note(clean(message));
            }
        }

        @Override
        public void showErrorDialog(final String message, final String title) {
            message(message, title);
        }

        @Override
        public boolean showConfirmDialog(final String message, final String title, final String yesButtonText,
                final String noButtonText, final boolean defaultYes) {
            return buttonsDialog(message, List.of(yesButtonText, noButtonText)) == 0;
        }

        @Override
        public int showOptionDialog(final String message, final String title, final FSkinProp icon,
                final List<String> options, final int defaultOption) {
            return buttonsDialog(message, options);
        }

        @Override
        public String showInputDialog(final String message, final String title, final FSkinProp icon,
                final String initialInput, final List<String> inputOptions, final boolean isNumeric) {
            if (inputOptions == null || inputOptions.isEmpty()) {
                return initialInput;                    // free text: not part of the protocol; Forge's default
            }
            return inputOptions.get(buttonsDialog(message, inputOptions));
        }

        @Override
        public boolean confirm(final CardView c, final String question, final boolean defaultIsYes,
                final List<String> options) {
            return buttonsDialog(question, options == null || options.size() < 2 ? List.of("Yes", "No") : options, c) == 0;
        }

        @Override
        public <T> List<T> getChoices(final String message, final int min, final int max, final List<T> choices,
                final List<T> selected, final FSerializableFunction<T, String> display) {
            if (min < 0 || max < 0) {                    // Forge showing a list, not asking: a note in the log
                final List<String> names = new ArrayList<>();
                for (final T c : choices) {
                    final String n = display == null ? label(c) : display.apply(c);
                    if (!n.startsWith("===")) {
                        names.add(n);
                    }
                }
                owner.note(clean(message) + ": " + String.join(", ", names));
                return new ArrayList<>();
            }
            if (min >= choices.size()) {
                return new ArrayList<>(choices);         // nothing to choose: all of them
            }
            if (max == 1 && choices.size() > NAME_LIST_MAX && choices.get(0) instanceof CardFaceView) {
                return List.of(nameCard(message, choices));   // "name a card": every card Forge knows
            }
            return choose(message, min, Math.max(min, Math.min(max, choices.size())), choices,
                    display == null ? null : display::apply, false);
        }

        @Override
        public <T> OrderResult<T> order(final String title, final String top, final int remainingObjectsMin,
                final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices,
                final CardView referenceCard, final boolean sideboardingMode, final boolean showRememberCheckbox) {
            // Forge's dual list: cards move from the source to the result, and remainingObjectsMin / Max bound
            // how many stay behind (-1: no bound). 0 / 0 means all of them, in order; anything else is a choice of
            // which to move, in order (many() comes here: scry's "cards to put on the bottom"). Treating every
            // call as "order them all" moved every scried card to the bottom.
            final List<T> result = new ArrayList<>(destChoices == null ? List.of() : destChoices);
            final int n = sourceChoices.size();
            final int minMove = remainingObjectsMax < 0 ? 0 : Math.max(0, n - remainingObjectsMax);
            final int maxMove = remainingObjectsMin < 0 ? n : Math.max(0, n - remainingObjectsMin);
            if (maxMove == 0) {
                return new OrderResult<>(result, false);
            }
            if (minMove == n && n == 1) {
                result.addAll(sourceChoices);
                return new OrderResult<>(result, false);
            }
            final String text = minMove == n && top != null ? title + " (the first is " + top + ")" : title;
            result.addAll(choose(text, minMove, maxMove, sourceChoices, null, true, referenceCard));
            return new OrderResult<>(result, false);
        }

        @Override
        public List<PaperCard> sideboard(final CardPool sideboard, final CardPool main, final String message) {
            return null;
        }

        @Override
        public GameEntityView chooseSingleEntityForEffect(final String title,
                final List<? extends GameEntityView> optionList, final DelayedReveal delayedReveal,
                final boolean isOptional) {
            final List<GameEntityView> picked = choose(title, isOptional ? 0 : 1, 1,
                    new ArrayList<GameEntityView>(optionList), null, false);
            return picked.isEmpty() ? null : picked.get(0);
        }

        @Override
        public List<GameEntityView> chooseEntitiesForEffect(final String title,
                final List<? extends GameEntityView> optionList, final int min, final int max,
                final DelayedReveal delayedReveal) {
            return choose(title, min, Math.max(min, Math.min(max, optionList.size())),
                    new ArrayList<GameEntityView>(optionList), null, false);
        }

        @Override
        public List<CardView> manipulateCardList(final String title, final Iterable<CardView> cards,
                final Iterable<CardView> manipulable, final boolean toTop, final boolean toBottom,
                final boolean toAnywhere) {
            final List<CardView> out = new ArrayList<>();
            cards.forEach(out::add);
            return out;                                  // keep the order (Forge's default)
        }

        @Override
        public Integer getInteger(final String message, final int min, final int max, final boolean sortDesc) {
            return number(message, min, max);
        }

        @Override
        public Integer getInteger(final String message, final int min, final int max, final int cutoff) {
            return number(message, min, max);
        }

        @Override
        public SpellAbilityView getAbilityToPlay(final CardView hostCard, final List<SpellAbilityView> abilities,
                final ITriggerEvent triggerEvent) {
            if (abilities.size() == 1) {
                return abilities.get(0);
            }
            final List<SpellAbilityView> picked = choose(hostCard.getName() + ": which ability?", 0, 1, abilities,
                    null, false);
            return picked.isEmpty() ? null : picked.get(0);
        }

        @Override
        public Map<CardView, Integer> assignCombatDamage(final CardView attacker, final List<CardView> blockers,
                final int damage, final GameEntityView defender, final boolean overrideOrder, final boolean maySkip) {
            return damage(attacker, blockers, damage, defender);
        }

        @Override
        public Map<Object, Integer> assignGenericAmount(final CardView effectSource, final Map<Object, Integer> target,
                final int amount, final boolean atLeastOne, final String amountLabel) {
            return divide(effectSource, target, amount, atLeastOne, amountLabel);
        }
    }
}
