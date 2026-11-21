/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks LLC
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes visionsort/bridge/HookedControllerAi.java: Forge's AI controller with every decision routed
 * through DecisionHook. Forge's PlayerController has ~113 decision methods and they change between
 * releases, so they are generated from the pinned Forge jar rather than written by hand.
 *
 * Run from VisionSort/ after upgrading Forge (single-file source launch, Java 17+):
 *   java -cp <forge>/forge-gui-desktop-<version>-jar-with-dependencies.jar ^
 *        tools/GenerateHookedController.java src/main/java/visionsort/bridge/HookedControllerAi.java
 *
 * Hooked:
 *  - every abstract method of PlayerController (the decisions and notifications Forge asks a
 *    controller for), as PlayerControllerAi implements it;
 *  - every concrete PlayerController method PlayerControllerAi overrides with its own logic
 *    (2.0.16: chooseBinary, chooseNumber, cheatShuffle), except NOT_DECISIONS. A new override in a
 *    later Forge is hooked by default rather than missed.
 * Concrete methods the AI doesn't override are Forge's defaults: getters, or final convenience
 * overloads that hand off to a hooked method. They're listed on stdout for review.
 */
public class GenerateHookedController {
    /** Overridden by PlayerControllerAi but not decisions: identity, sideboarding, bookkeeping. */
    private static final java.util.Set<String> NOT_DECISIONS = java.util.Set.of(
            "isAI", "getSideboardingReport", "sideboardsByItself", "complainCardsCantPlayWell", "resetAtEndOfTurn");

    private static final Map<Class<?>, String> BOXED = Map.of(
            boolean.class, "Boolean", int.class, "Integer", long.class, "Long", double.class, "Double",
            float.class, "Float", short.class, "Short", byte.class, "Byte", char.class, "Character");

    public static void main(final String[] args) throws Exception {
        final Class<?> base = Class.forName("forge.game.player.PlayerController");
        final Class<?> ai = Class.forName("forge.ai.PlayerControllerAi");
        final List<Method> hooked = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        final List<String> concrete = new ArrayList<>();

        final Method[] declared = base.getDeclaredMethods();
        Arrays.sort(declared, Comparator.comparing(GenerateHookedController::signature));
        for (final Method m : declared) {
            final int mod = m.getModifiers();
            if (m.isSynthetic() || m.isBridge() || Modifier.isStatic(mod) || Modifier.isPrivate(mod)) {
                continue;
            }
            final Method impl = implementation(ai, m);
            if (!Modifier.isAbstract(mod)) {
                final boolean aiOverrides = impl.getDeclaringClass() == ai;
                if (!aiOverrides || NOT_DECISIONS.contains(m.getName())) {
                    concrete.add(signature(m) + (aiOverrides ? " - AI override, not a decision" : ""));
                    continue;
                }
            }
            if (Modifier.isFinal(impl.getModifiers())) {
                skipped.add(signature(m) + " (final in PlayerControllerAi)");
            } else if (m.getExceptionTypes().length > 0) {
                skipped.add(signature(m) + " (declares checked exceptions)");
            } else {
                hooked.add(impl);
            }
        }

        Files.writeString(Path.of(args[0]), source(hooked), StandardCharsets.UTF_8);
        System.out.println("hooked " + hooked.size() + " decision methods into " + args[0]);
        skipped.forEach(s -> System.out.println("NOT HOOKED: " + s));
        System.out.println("concrete PlayerController methods (not hooked, review on upgrade):");
        concrete.forEach(s -> System.out.println("  " + s));
    }

    /** The method PlayerControllerAi runs for m: its own declaration (public or protected), else m. */
    private static Method implementation(final Class<?> ai, final Method m) {
        try {
            return ai.getDeclaredMethod(m.getName(), m.getParameterTypes());
        } catch (NoSuchMethodException e) {
            return m;
        }
    }

    private static String source(final List<Method> methods) {
        final StringBuilder s = new StringBuilder();
        s.append("""
                /*
                 * VisionSort Forge bridge
                 * Copyright (C) 2026 Bosch SoftWorks LLC
                 *
                 * This program is free software: you can redistribute it and/or modify it under the terms of the
                 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
                 * License, or (at your option) any later version. See LICENSE at the root of this repository.
                 */
                // GENERATED by tools/GenerateHookedController.java from Forge's jar - do not edit by hand.
                package visionsort.bridge;

                /** Forge's AI controller with every decision routed through {@link DecisionHook}. */
                @SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
                public class HookedControllerAi extends forge.ai.PlayerControllerAi {
                    private final DecisionHook hook;

                    public HookedControllerAi(final forge.game.Game game, final forge.game.player.Player player,
                            final forge.LobbyPlayer lobbyPlayer, final DecisionHook hook) {
                        super(game, player, lobbyPlayer);
                        this.hook = hook;
                    }
                """);
        for (final Method m : methods) {
            s.append('\n').append(method(m));
        }
        s.append("}\n");
        return s.toString();
    }

    private static String method(final Method m) {
        final Type[] params = m.getGenericParameterTypes();
        final List<String> decl = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < params.length; i++) {
            decl.add("final " + typeName(params[i]) + " a" + i);
            names.add("a" + i);
        }
        final String call = "super." + m.getName() + "(" + String.join(", ", names) + ")";
        final String argArray = "new Object[] {" + String.join(", ", names) + "}";
        final Class<?> ret = m.getReturnType();
        final String visibility = Modifier.isProtected(m.getModifiers()) ? "protected" : "public";

        final StringBuilder s = new StringBuilder("    @Override\n    ").append(visibility).append(' ');
        final TypeVariable<Method>[] tps = m.getTypeParameters();
        if (tps.length > 0) {
            s.append('<').append(Arrays.stream(tps).map(GenerateHookedController::typeParam)
                    .collect(Collectors.joining(", "))).append("> ");
        }
        s.append(typeName(m.getGenericReturnType())).append(' ').append(m.getName())
                .append('(').append(String.join(", ", decl)).append(") {\n");
        final String hookCall = "hook.call(this, \"" + m.getName() + "\", " + argArray + ", ";
        if (ret == void.class) {
            s.append("        ").append(hookCall).append("() -> { ").append(call).append("; return null; });\n");
        } else {
            final String cast = ret.isPrimitive() ? BOXED.get(ret) : typeName(m.getGenericReturnType());
            s.append("        return (").append(cast).append(") ").append(hookCall)
                    .append("() -> ").append(call).append(");\n");
        }
        return s.append("    }\n").toString();
    }

    private static String typeParam(final TypeVariable<?> tv) {
        final List<String> bounds = Arrays.stream(tv.getBounds()).map(GenerateHookedController::typeName)
                .filter(b -> !b.equals("java.lang.Object")).toList();
        return bounds.isEmpty() ? tv.getName() : tv.getName() + " extends " + String.join(" & ", bounds);
    }

    /** A type as Java source: nested classes with '.', not the binary name's '$'. */
    private static String typeName(final Type t) {
        return t.getTypeName().replace('$', '.');
    }

    private static String signature(final Method m) {
        return m.getName() + Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName)
                .collect(Collectors.joining(", ", "(", ")"));
    }
}
