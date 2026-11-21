/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks LLC
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.google.gson.JsonObject;

import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.player.PlayerController.BinaryChoiceType;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;
import forge.game.spellability.TargetRestrictions;
import forge.util.collect.FCollectionView;

/**
 * Novice's deliberate mistakes: at a set rate per kind, a legal but (usually) worse answer than the
 * AI's. Each is legal by construction or checked with Forge's own validator and undone if it fails:
 *
 *   skip_land     the AI wanted to play a land -> pass priority instead (always legal)
 *   skip_spell    the AI wanted to cast / activate -> pass instead
 *   target        the AI is about to cast a single-target spell -> another legal target
 *                 (TargetRestrictions candidates that sa.canTarget(); not divided damage)
 *   mulligan      keep <-> mulligan (Forge only asks while a mulligan is allowed)
 *   choose        "choose one of these" -> another of the options Forge offered
 *   decline       yes -> no on an optional action (not no -> yes: that could commit to a cost
 *                 the AI can't pay)
 *   binary        flip a two-way choice, only where both answers are always possible (SAFE_BINARY)
 *   attack_hold   take one attacker back             } checked with CombatUtil.validateAttackers
 *   attack_extra  send one more creature in          } (must-attack rules); no creature with an
 *                                                       attack cost (Propaganda and the like)
 *   block_skip    take one block back                } checked with CombatUtil.validateBlocks
 *   block_extra   add one more block                 } (must-block rules); no block with a cost
 *
 * The AI picks targets while it plans, so "target" acts on the spell chooseSpellAbilityToPlay
 * returns, before it's cast. Nested decisions (asked while the seat pays a cost or resolves an
 * effect) only get choose / decline / binary, which stay legal on their own.
 *
 * Not yet: numbers (a chosen number can feed a cost paid later, so it must be checked payable).
 * Known side effect: when skip_* passes instead of a spell, the AI has already chosen targets / X
 * for it; Forge chooses them afresh the next time the spell is considered.
 *
 * Its random numbers come from its own generator, seeded from the game seed and the seat, never
 * from Forge's: a Novice game is as repeatable as any other.
 */
final class MistakePolicy implements Policy {
    static final List<String> KINDS = List.of("skip_land", "skip_spell", "target", "mulligan", "choose", "decline",
            "binary", "attack_hold", "attack_extra", "block_skip", "block_extra");

    /** Binary choices where flipping the AI's answer is always legal (not add/remove counters). */
    private static final Set<BinaryChoiceType> SAFE_BINARY = EnumSet.of(BinaryChoiceType.HeadsOrTails,
            BinaryChoiceType.PlayOrDraw, BinaryChoiceType.OddsOrEvens, BinaryChoiceType.LeftOrRight,
            BinaryChoiceType.TapOrUntap, BinaryChoiceType.UntapOrLeaveTapped);

    private final Map<String, Double> rates;
    private final Random random;

    private MistakePolicy(final Map<String, Double> rates, final long seed) {
        this.rates = rates;
        this.random = new Random(seed);
    }

    /** From {"skip_land":0.05, "choose":0.25, ...}; unknown kinds are refused, so a typo can't pass silently. */
    static Policy from(final JsonObject json, final long gameSeed, final int seat) {
        if (json == null || json.size() == 0) {
            return Policy.NONE;
        }
        final Map<String, Double> rates = new HashMap<>();
        for (final String kind : json.keySet()) {
            if (!KINDS.contains(kind)) {
                throw new IllegalArgumentException("Unknown mistake kind " + kind + "; known: " + KINDS);
            }
            final double r = json.get(kind).getAsDouble();
            if (r < 0 || r > 1) {
                throw new IllegalArgumentException("Mistake rate for " + kind + " must be 0-1, not " + r);
            }
            rates.put(kind, r);
        }
        return new MistakePolicy(rates, gameSeed * 1_000_003L + seat);
    }

    @Override
    public String name() {
        return "mistake";
    }

    @Override
    public Object adjust(final String decision, final Object[] args, final Object aiChoice, final boolean nested) {
        if (nested) {
            // only kinds that stay legal on their own while a cost is paid or an effect resolves
            return switch (decision) {
                case "chooseSingleEntityForEffect", "confirmAction", "chooseBinary" -> adjust(decision, args, aiChoice, false);
                default -> aiChoice;
            };
        }
        switch (decision) {
            case "chooseSpellAbilityToPlay": {
                if (!(aiChoice instanceof List<?> l) || l.isEmpty() || !(l.get(0) instanceof SpellAbility sa)) {
                    return aiChoice;
                }
                if (roll(sa.isLandAbility() ? "skip_land" : "skip_spell")) {
                    return null;
                }
                return retarget(sa) ? CHANGED_IN_PLACE : aiChoice;
            }
            case "mulliganKeepHand":
                return roll("mulligan") ? Boolean.valueOf(!(Boolean) aiChoice) : aiChoice;
            case "chooseSingleEntityForEffect": {
                if (!(args[0] instanceof FCollectionView<?> options) || options.size() < 2 || !roll("choose")) {
                    return aiChoice;
                }
                final List<Object> others = new ArrayList<>();
                for (final Object o : options) {
                    if (o != aiChoice) {
                        others.add(o);
                    }
                }
                return others.isEmpty() ? aiChoice : others.get(random.nextInt(others.size()));
            }
            case "confirmAction":
                return Boolean.TRUE.equals(aiChoice) && roll("decline") ? Boolean.FALSE : aiChoice;
            case "chooseBinary":
                return args.length > 2 && args[2] instanceof BinaryChoiceType kind
                        && SAFE_BINARY.contains(kind) && roll("binary") ? Boolean.valueOf(!(Boolean) aiChoice) : aiChoice;
            case "declareAttackers":
                return args.length > 1 && args[0] instanceof Player p && args[1] instanceof Combat c
                        && attackMistake(p, c) ? CHANGED_IN_PLACE : aiChoice;
            case "declareBlockers":
                return args.length > 1 && args[0] instanceof Player p && args[1] instanceof Combat c
                        && blockMistake(p, c) ? CHANGED_IN_PLACE : aiChoice;
            default:
                return aiChoice;
        }
    }

    /** Another legal target for a single-target spell; undone if Forge doesn't accept it. */
    private boolean retarget(final SpellAbility sa) {
        if (!sa.usesTargeting() || sa.isDividedAsYouChoose() || !roll("target")) {
            return false;
        }
        final TargetRestrictions tr = sa.getTargetRestrictions();
        final TargetChoices targets = sa.getTargets();
        final Card host = sa.getHostCard();
        if (tr == null || targets == null || targets.size() != 1
                || tr.getMinTargets(host, sa) != 1 || tr.getMaxTargets(host, sa) != 1) {
            return false;
        }
        final GameObject current = targets.get(0);
        final List<GameEntity> others = new ArrayList<>();
        // false = players AND cards (Forge's flag is "only non-card": true gives players only)
        for (final GameEntity e : tr.getAllCandidates(sa, false)) {
            if (e != current && sa.canTarget(e)) {
                others.add(e);
            }
        }
        if (others.isEmpty()) {
            return false;
        }
        final GameEntity pick = others.get(random.nextInt(others.size()));
        targets.remove(current);
        targets.add(pick);
        if (!sa.isTargetNumberValid() || !sa.canTarget(pick)) {
            targets.remove(pick);
            targets.add(current);
            return false;
        }
        return true;
    }

    private boolean attackMistake(final Player attacker, final Combat combat) {
        if (!combat.getAttackers().isEmpty() && roll("attack_hold")) {
            final Card a = pick(combat.getAttackers());
            final GameEntity defender = combat.getDefenderByAttacker(a);
            combat.removeFromCombat(a);
            if (CombatUtil.validateAttackers(combat)) {
                return true;
            }
            combat.addAttacker(a, defender);   // must attack: undo
        }
        if (roll("attack_extra")) {
            final List<Card> idle = new ArrayList<>();
            for (final Card c : attacker.getCreaturesInPlay()) {
                if (!combat.isAttacking(c) && CombatUtil.canAttack(c)) {
                    idle.add(c);
                }
            }
            if (!idle.isEmpty()) {
                final Card c = pick(idle);
                final List<GameEntity> defenders = new ArrayList<>();
                for (final GameEntity d : combat.getDefenders()) {
                    if (CombatUtil.canAttack(c, d) && CombatUtil.getAttackCost(c.getGame(), c, d) == null) {
                        defenders.add(d);
                    }
                }
                if (!defenders.isEmpty()) {
                    combat.addAttacker(c, defenders.get(random.nextInt(defenders.size())));
                    if (CombatUtil.validateAttackers(combat)) {
                        return true;
                    }
                    combat.removeFromCombat(c);
                }
            }
        }
        return false;
    }

    private boolean blockMistake(final Player defender, final Combat combat) {
        final List<Card> own = new ArrayList<>();
        for (final Card b : combat.getAllBlockers()) {
            if (b.getController() == defender) {
                own.add(b);
            }
        }
        if (!own.isEmpty() && roll("block_skip")) {
            final Card b = pick(own);
            final List<Card> blocked = new ArrayList<>(combat.getAttackersBlockedBy(b));
            combat.undoBlockingAssignment(b);
            if (CombatUtil.validateBlocks(combat, defender) == null) {
                return true;
            }
            for (final Card a : blocked) {   // must block: undo
                combat.addBlocker(a, b);
            }
        }
        if (roll("block_extra")) {
            final List<Card[]> options = new ArrayList<>();
            for (final Card b : defender.getCreaturesInPlay()) {
                if (combat.isBlocking(b)) {
                    continue;
                }
                for (final Card a : combat.getAttackers()) {
                    if (CombatUtil.canBlock(a, b, combat) && CombatUtil.getBlockCost(b.getGame(), b, a) == null) {
                        options.add(new Card[] {a, b});
                    }
                }
            }
            if (!options.isEmpty()) {
                final Card[] o = options.get(random.nextInt(options.size()));
                combat.addBlocker(o[0], o[1]);
                if (CombatUtil.validateBlocks(combat, defender) == null) {
                    return true;
                }
                combat.removeBlockAssignment(o[0], o[1]);
            }
        }
        return false;
    }

    private Card pick(final List<Card> cards) {
        return cards.get(random.nextInt(cards.size()));
    }

    private boolean roll(final String kind) {
        final double r = rates.getOrDefault(kind, 0.0);
        return r > 0 && random.nextDouble() < r;
    }
}
