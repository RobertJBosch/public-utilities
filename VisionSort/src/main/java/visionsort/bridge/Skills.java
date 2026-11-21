/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks LLC
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.localinstance.properties.ForgeConstants;

/**
 * A seat's AI settings: one of Forge's profiles plus overrides of any of its settings (AiProps),
 * and look-ahead off / on / max.
 *
 * Forge looks a setting up by the seat's profile name in AiProfileUtil's private static map of
 * loaded profiles, on every use. So each seat with overrides or look-ahead gets its own profile
 * in that map for the length of the game (register / unregister): the base profile's values, then
 * the look-ahead settings, then the overrides. Nothing is written to Forge's folder. This writes
 * into a private field of the pinned Forge version; profileMap() fails loudly if a Forge upgrade
 * renames it.
 *
 * Look-ahead, made repeatable:
 *  - Forge's own "max" (Game.AI_FLAT_MONTE_CARLO_MAX) hard-codes a 1.5 s / 6 s time budget and
 *    then scales the search to what the PC manages in that time, so its games differ from PC to
 *    PC and run to run. It is never used here.
 *  - Look-ahead runs in Forge's "on" mode with the time budgets (FLAT_MC_DECISION_BUDGET_MS /
 *    _TURN_BUDGET_MS) set far beyond what a decision takes, so the search is bounded by its counts
 *    (candidates, playouts) alone - the same on any PC.
 *  - "max" here = "on" with the counts Forge's max mode uses (MAX_LOOKAHEAD, copied from
 *    FlatMonteCarloPicker$Config.maximized() in 2.0.16).
 *  - Left over: Forge gives the AI inside each imagined playout a hard-coded 2 s per decision.
 *    Those decisions are small, so it should never be reached, but it is a clock.
 */
final class Skills {
    enum Lookahead { OFF, ON, MAX }

    /** An untimed budget: ~11 days in ms (fits an int; Forge multiplies it to nanoseconds as a long). */
    private static final String UNTIMED_MS = "999999999";
    private static final Map<AiProps, String> MAX_LOOKAHEAD = new EnumMap<>(AiProps.class);

    static {
        MAX_LOOKAHEAD.put(AiProps.FLAT_MC_MAX_CANDIDATES, "8");
        MAX_LOOKAHEAD.put(AiProps.FLAT_MC_ROLLOUTS_PER_CANDIDATE, "8");
        MAX_LOOKAHEAD.put(AiProps.FLAT_MC_EXTRA_ROUNDS, "6");
        MAX_LOOKAHEAD.put(AiProps.FLAT_MC_MAX_PLAYOUT_PLAYS, "10");
        MAX_LOOKAHEAD.put(AiProps.FLAT_MC_COMBO_PLAYOUT_PLAYS, "20");
        MAX_LOOKAHEAD.put(AiProps.FLAT_MC_TARGET_VARIANTS, "3");
        MAX_LOOKAHEAD.put(AiProps.FLAT_MC_PRUNING, "true");
        MAX_LOOKAHEAD.put(AiProps.FLAT_MC_CATEGORY_HOLD, "true");
        MAX_LOOKAHEAD.put(AiProps.FLAT_MC_ADAPTIVE_PLAYOUT, "true");
        MAX_LOOKAHEAD.put(AiProps.FLAT_MC_CONSIDER_HOLDING, "true");
        MAX_LOOKAHEAD.put(AiProps.FLAT_MC_EXTRA_CANDIDATES, "5");
        MAX_LOOKAHEAD.put(AiProps.FLAT_MC_BROAD_DISCOVERY, "true");
        MAX_LOOKAHEAD.put(AiProps.FLAT_MC_OPPONENT_LOOKAHEAD, "50");
    }

    private Skills() {}

    static Lookahead lookahead(final String s) {
        for (final Lookahead l : Lookahead.values()) {
            if (l.name().equalsIgnoreCase(s)) {
                return l;
            }
        }
        throw new IllegalArgumentException("lookahead must be off, on or max, not " + s);
    }

    /** Forge's profiles VisionSort offers (its "Random ..." menu entries aren't profiles). */
    static List<String> forgeProfiles() {
        return AiProfileUtil.getProfilesDisplayList().stream().filter(p -> !p.startsWith("Random")).toList();
    }

    /**
     * Registers the seat's profile for this game and returns its name. base = one of Forge's
     * profiles ("" = Default); settings = {"AIPROP": value}; checked against Forge's AiProps.
     */
    static String register(final String gameTag, final String seat, final String base, final JsonObject settings,
            final Lookahead lookahead) {
        final String baseName = base == null || base.isEmpty() ? "Default" : base;
        if (!forgeProfiles().contains(baseName)) {
            throw new IllegalArgumentException("Unknown AI profile " + baseName + "; Forge has " + forgeProfiles());
        }
        final Map<String, Map<AiProps, String>> profiles = profileMap();
        final Map<AiProps, String> values = new EnumMap<>(AiProps.class);
        final Map<AiProps, String> baseValues = profiles.get(baseName);
        if (baseValues != null) {
            values.putAll(baseValues);
        }
        if (lookahead == Lookahead.MAX) {
            values.putAll(MAX_LOOKAHEAD);
        }
        if (settings != null) {
            for (final Map.Entry<String, JsonElement> e : settings.entrySet()) {
                final AiProps prop = prop(e.getKey());
                values.put(prop, checked(prop, e.getValue().getAsString()));
            }
        }
        if (lookahead != Lookahead.OFF) {
            values.put(AiProps.FLAT_MC_DECISION_BUDGET_MS, UNTIMED_MS);
            values.put(AiProps.FLAT_MC_TURN_BUDGET_MS, UNTIMED_MS);
        }
        final String name = "VisionSort " + gameTag + " " + seat;
        profiles.put(name, values);
        return name;
    }

    static void unregister(final String name) {
        profileMap().remove(name);
    }

    /**
     * Every setting Forge has, for the skill editor: {"settings":[{"key", "default", "type",
     * "help"}...], "profiles":{"Default":{"KEY":value,...},...}}. "help" is the comment above the
     * key in Forge's Default.ai; "type" is bool / int / text, from Forge's own default.
     */
    static JsonObject catalogue() throws IOException {
        final Map<String, String> help = comments(Path.of(ForgeConstants.AI_PROFILE_DIR, "Default.ai"));
        final JsonArray settings = new JsonArray();
        for (final AiProps p : AiProps.values()) {
            final JsonObject s = new JsonObject();
            s.addProperty("key", p.name());
            s.addProperty("default", p.getDefault());
            s.addProperty("type", type(p.getDefault()));
            s.addProperty("help", help.getOrDefault(p.name(), ""));
            settings.add(s);
        }
        final JsonObject profiles = new JsonObject();
        final Map<String, Map<AiProps, String>> loaded = profileMap();
        for (final String name : forgeProfiles()) {
            final JsonObject values = new JsonObject();
            final Map<AiProps, String> v = loaded.get(name);
            if (v != null) {
                for (final AiProps p : AiProps.values()) {
                    if (v.containsKey(p)) {
                        values.addProperty(p.name(), v.get(p));
                    }
                }
            }
            profiles.add(name, values);
        }
        final JsonObject out = new JsonObject();
        out.add("settings", settings);
        out.add("profiles", profiles);
        return out;
    }

    private static AiProps prop(final String key) {
        try {
            return AiProps.valueOf(key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown AI setting " + key + " (not in this Forge version's AiProps)");
        }
    }

    /** A value of the same type as Forge's default for the setting, or a clear error. */
    private static String checked(final AiProps prop, final String value) {
        switch (type(prop.getDefault())) {
            case "bool":
                if (!value.equals("true") && !value.equals("false")) {
                    throw new IllegalArgumentException(prop + " must be true or false, not " + value);
                }
                return value;
            case "int":
                try {
                    return String.valueOf(Integer.parseInt(value.trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(prop + " must be a whole number, not " + value);
                }
            default:
                return value;
        }
    }

    private static String type(final String def) {
        if (def == null) {
            return "text";
        }
        if (def.equals("true") || def.equals("false")) {
            return "bool";
        }
        try {
            Integer.parseInt(def.trim());
            return "int";
        } catch (NumberFormatException e) {
            return "text";
        }
    }

    /** KEY -> the comment lines right above it in a Forge .ai file, joined. */
    private static Map<String, String> comments(final Path file) throws IOException {
        final Map<String, String> out = new LinkedHashMap<>();
        final List<String> pending = new ArrayList<>();
        boolean afterKey = false;
        for (final String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            final String line = raw.trim();
            if (line.isEmpty()) {
                pending.clear();
                afterKey = false;
            } else if (line.startsWith("#")) {
                if (afterKey) {   // a new comment after a key starts a new block
                    pending.clear();
                    afterKey = false;
                }
                pending.add(line.substring(1).trim());
            } else if (line.contains("=")) {
                // keys right under one comment (no blank line between) share it
                out.put(line.substring(0, line.indexOf('=')).trim(), String.join(" ", pending));
                afterKey = true;
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<AiProps, String>> profileMap() {
        try {
            final Field f = AiProfileUtil.class.getDeclaredField("loadedProfiles");
            f.setAccessible(true);
            return (Map<String, Map<AiProps, String>>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("This Forge version keeps its AI profiles differently; the bridge needs updating", e);
        }
    }
}
