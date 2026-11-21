/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks
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
import forge.card.MagicColor;
import forge.deck.CardPool;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameEntityView;
import forge.game.card.Card;
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
import forge.gamemodes.match.input.InputPassPriority;
import forge.gamemodes.match.input.InputPayMana;
import forge.gamemodes.match.input.InputSelectTargets;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
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
            CompletableFuture<JsonObject> reply) {}

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
    private final String[] buttons = {"", ""};
    private final boolean[] enabled = {false, false};
    // mulligans and practice
    private int mulligans;
    private volatile boolean redrawRequested;
    volatile boolean practiceUsed;
    private final JsonArray answers = new JsonArray();

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
        // A new input's decision is built when the input shows its prompt (Gui.showPromptMessage): only then
        // are its buttons and selection set. Watching the input queue instead races Forge's own prompt update.
    }

    int seat() {
        return seats.indexOf(me);
    }

    JsonArray answers() {
        return answers;
    }

    void setStops(final JsonObject s) {
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

    /** The game is ending: release any dialog that waits for an answer. */
    void abort() {
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
        owner.flush();                                // what happened first, then what to decide
        final JsonObject m = new JsonObject();
        m.add("state", owner.state());
        m.add("decision", d);
        pending = new Pending(id, kind, input, options, d, reply);
        owner.step();
        sink.send("decision", m);
        return id;
    }

    private void refuse(final int id, final String reason) {
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
        answers.add(r);
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
        send("pay_mana", "Pay " + costText + (host == null ? "" : " for " + host.getName()), f, in, List.of(), null, reuse);
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
        combat.getAttackers().forEach(c -> attackers.add(c.getId()));
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
        send("confirm", clean(prompt), f, in, which, null, reuse);
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
        final JsonArray opts = new JsonArray();
        labels.forEach(opts::add);
        final JsonObject f = new JsonObject();
        f.add("options", opts);
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
        final JsonObject a = ask("damage", "Divide " + attacker.getName() + "'s " + amount + " damage", f, x -> {
            if (x.has("auto")) {
                return null;
            }
            if (!x.has("assign")) {
                return "Assign the damage, or let Forge do it";
            }
            int sum = x.has("to_defender") ? x.get("to_defender").getAsInt() : 0;
            if (sum > 0 && !trample) {
                return "Only an attacker with trample assigns damage to the player";
            }
            for (final JsonElement e : x.getAsJsonArray("assign")) {
                final int id = e.getAsJsonArray().get(0).getAsInt();
                if (blockers.stream().noneMatch(b -> b.getId() == id)) {
                    return "Assign damage only to the blockers";
                }
                sum += e.getAsJsonArray().get(1).getAsInt();
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
            return buttonsDialog(question, options == null || options.size() < 2 ? List.of("Yes", "No") : options) == 0;
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
            return choose(message, min, Math.max(min, Math.min(max, choices.size())), choices,
                    display == null ? null : display::apply, false);
        }

        @Override
        public <T> OrderResult<T> order(final String title, final String top, final int remainingObjectsMin,
                final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices,
                final CardView referenceCard, final boolean sideboardingMode, final boolean showRememberCheckbox) {
            if (sourceChoices.size() <= 1) {
                final List<T> all = new ArrayList<>(sourceChoices);
                if (destChoices != null) {
                    all.addAll(destChoices);
                }
                return new OrderResult<>(all, false);
            }
            final List<T> ordered = choose(title + (top == null ? "" : " (first = " + top + ")"), sourceChoices.size(),
                    sourceChoices.size(), sourceChoices, null, true);
            if (destChoices != null) {
                ordered.addAll(destChoices);
            }
            return new OrderResult<>(ordered, false);
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
            // dividing an effect's amount (damage, counters) among targets: as even as it goes, for now
            final Map<Object, Integer> out = new java.util.LinkedHashMap<>();
            final List<Object> keys = new ArrayList<>(target.keySet());
            int left = amount;
            for (int i = 0; i < keys.size(); i++) {
                final int n = i == keys.size() - 1 ? left : Math.max(atLeastOne ? 1 : 0, amount / keys.size());
                out.put(keys.get(i), Math.min(n, left));
                left -= Math.min(n, left);
            }
            return out;
        }
    }
}
