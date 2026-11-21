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

import forge.game.Game;
import forge.game.combat.CombatView;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;

/**
 * Forge numbers spell abilities, triggers, static abilities, replacement effects, stack entries and
 * games from counters that run for the whole process, and SpellAbility.hashCode is its number. So
 * a hash set of abilities iterates in an order that depends on how many objects every earlier game
 * made, and the AI walks such sets. Seen (bridge 0.6.0, Krenko v Uril seed 103): two identical
 * Goblin tokens attacked in the other order after other games - a different game from the same
 * seed - while the same game first in a fresh process matched itself.
 *
 * Before each game the counters go back to the same values, so a game is the same whatever ran
 * before it in the process. The card abilities Forge makes outside games (loading its card
 * database, resolving names) keep their lower numbers; games start at BASE, far above them, so a
 * game's objects never share a number with one of those.
 */
final class GameIds {
    /** Where a game's ability numbers start: above anything made outside games (~10^5 at most). */
    private static final int BASE = 50_000_000;

    private GameIds() {}

    static void reset() {
        set(SpellAbility.class, "maxId", BASE);
        set(Trigger.class, "maxId", BASE);
        set(StaticAbility.class, "maxId", BASE);
        set(ReplacementEffect.class, "maxId", BASE);
        set(SpellAbilityStackInstance.class, "maxId", 0);
        set(Game.class, "maxId", 0);
        set(CombatView.class, "nextId", -2);       // Forge's own starting value
    }

    private static void set(final Class<?> cls, final String field, final int value) {
        try {
            final Field f = cls.getDeclaredField(field);
            f.setAccessible(true);
            f.setInt(null, value);
        } catch (ReflectiveOperationException e) {
            // a Forge version without this counter: games stay repeatable in a fresh process only
            throw new IllegalStateException("Forge's " + cls.getSimpleName() + "." + field + " changed: " + e, e);
        }
    }
}
