/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.gson.JsonObject;

import forge.game.spellability.SpellAbility;
import forge.util.collect.FCollectionView;

/**
 * Novice's deliberate mistakes: at a set rate per kind of decision, a legal but (usually) worse
 * answer than the AI's. Every mistake here is legal by construction:
 *
 *   skip_land   chooseSpellAbilityToPlay wanted to play a land -> pass instead (always legal)
 *   skip_spell  chooseSpellAbilityToPlay wanted to cast / activate -> pass instead
 *   mulligan    mulliganKeepHand -> the other answer (keeping and mulliganing are both allowed)
 *   choose      chooseSingleEntityForEffect -> another of the options Forge offered
 *   decline     confirmAction said yes to an optional action -> no (yes -> no only: a yes the AI
 *               turned down might commit it to a cost it can't pay)
 *   binary      chooseBinary -> the other answer (both sides are offered)
 *   number      chooseNumber(sa, title, min, max) -> another number in [min, max]
 *
 * Not yet: attacks, blocks and targets. Taking an attacker or blocker back can break "must attack /
 * must block" requirements, and targets need Forge's legality checks; they come with the skill files.
 *
 * Its random numbers come from its own generator, seeded from the game seed and the seat, never
 * from Forge's: a Novice game is as repeatable as any other.
 */
final class MistakePolicy implements Policy {
    static final List<String> KINDS = List.of("skip_land", "skip_spell", "mulligan", "choose", "decline", "binary", "number");

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
        final Map<String, Double> rates = new java.util.HashMap<>();
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
    public Object adjust(final String decision, final Object[] args, final Object aiChoice) {
        switch (decision) {
            case "chooseSpellAbilityToPlay": {
                if (!(aiChoice instanceof List<?> l) || l.isEmpty() || !(l.get(0) instanceof SpellAbility sa)) {
                    return aiChoice;
                }
                return roll(sa.isLandAbility() ? "skip_land" : "skip_spell") ? null : aiChoice;
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
                return roll("binary") ? Boolean.valueOf(!(Boolean) aiChoice) : aiChoice;
            case "chooseNumber": {
                // only the (sa, title, min, max[, params]) forms: the others pick from a list
                if (args.length < 4 || !(args[2] instanceof Integer min) || !(args[3] instanceof Integer max)
                        || max <= min || !roll("number")) {
                    return aiChoice;
                }
                int n = min + random.nextInt(max - min);
                if (n >= (Integer) aiChoice) {
                    n++;   // any number in range except the AI's
                }
                return Integer.valueOf(n);
            }
            default:
                return aiChoice;
        }
    }

    private boolean roll(final String kind) {
        final double r = rates.getOrDefault(kind, 0.0);
        return r > 0 && random.nextDouble() < r;
    }
}
