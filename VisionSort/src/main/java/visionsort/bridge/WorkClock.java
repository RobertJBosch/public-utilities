/*
 * VisionSort Forge bridge
 * Copyright (C) 2026 Bosch SoftWorks LLC
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE at the root of this repository.
 */
package visionsort.bridge;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The AI's clock, made of work instead of time: a Java agent (-javaagent:the bridge jar) that rewrites Forge's AI
 * classes (forge/ai/...) as they load, so their System.nanoTime() and currentTimeMillis() read this counter.
 *
 * Why: Forge 2.0.16's look-ahead (FlatMonteCarloPicker) is bounded by time as well as by counts - a decision
 * budget (clamped to 10 s however high a profile sets it), a turn budget (60 s), a rollout deadline, a first
 * rollout timed to cut the candidates to what "this device" can afford, and a pause of a few turns after slow
 * decisions. Whichever of those bites first depends on the machine and the moment, so Hard and Hardest games
 * didn't repeat: the same seed played differently (seen in the 2026-11-22 recheck). Counted, the clock only moves
 * when the AI asks it the time, by STEP each time - so those limits are reached after the same work on every PC,
 * every run (STEP is sized so they bind after about as much work as they did on real time). The cost: a slow PC
 * waits longer on a decision.
 *
 * Only forge/ai is rewritten: the bridge's own timeouts (a game's time limit, a stuck game) keep real time.
 */
public final class WorkClock {
    /**
     * How far the clock moves each time the AI asks it the time: the real time one question stood for, measured
     * 2026-11-24 (Hardest v Hardest on this PC: a median 57 questions a second, 0-106 over 15 minutes), so the
     * budgets bind after about as much work as they did on real time - only now the same work everywhere. Not a
     * nanosecond: with it no budget was ever reached, and the AI's look-ahead re-aimed an Equip {0} ability 1,872
     * times in one turn (its turn budget is what used to stop it), so a game ran 15+ minutes.
     * (-Dvisionsort.clock.step_ns=... to measure with another.)
     */
    static final long STEP = Long.getLong("visionsort.clock.step_ns", 17_000_000L);
    private static final long START = 1_000_000_000L;
    private static final AtomicLong NOW = new AtomicLong(START);
    private static final String OWNER = "java/lang/System";
    private static final ClassDesc SELF = ClassDesc.of(WorkClock.class.getName());
    private static volatile boolean installed;
    private static final AtomicLong REWRITTEN = new AtomicLong();

    private WorkClock() {
    }

    public static long nanoTime() {
        return NOW.addAndGet(STEP);
    }

    public static long currentTimeMillis() {
        return NOW.addAndGet(STEP) / 1_000_000L;
    }

    /** Whether the agent is in place (the bridge says so in its ready message). */
    static boolean installed() {
        return installed;
    }

    /** How many of Forge's AI classes read this clock so far. */
    static long rewritten() {
        return REWRITTEN.get();
    }

    public static void premain(final String args, final Instrumentation inst) {
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(final ClassLoader loader, final String name, final Class<?> redefining,
                                    final ProtectionDomain domain, final byte[] bytes) {
                if (name == null || !name.startsWith("forge/ai/")) {
                    return null;
                }
                try {
                    return rewrite(bytes);
                } catch (Throwable t) {
                    System.err.println("WorkClock: left " + name + " as it was: " + t);
                    return null;                       // the class keeps real time; the game still runs
                }
            }
        });
        installed = true;
        if (Boolean.getBoolean("visionsort.clock.diag")) {
            final Thread t = new Thread(WorkClock::report, "work clock diag");
            t.setDaemon(true);
            t.start();
        }
    }

    /** -Dvisionsort.clock.diag=true: every 10 s, how many times the AI asked the time (to size STEP from). */
    private static void report() {
        long lastReads = 0;
        long last = System.nanoTime();
        while (true) {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                return;
            }
            final long reads = (NOW.get() - START) / STEP;
            final long now = System.nanoTime();
            System.err.printf("WORKCLOCK reads=%d +%d in %.1f s%n", reads, reads - lastReads, (now - last) / 1e9);
            lastReads = reads;
            last = now;
        }
    }

    private static boolean isClockCall(final CodeElement e) {
        return e instanceof InvokeInstruction i && i.opcode() == Opcode.INVOKESTATIC
                && i.owner().asInternalName().equals(OWNER)
                && (i.name().equalsString("nanoTime") || i.name().equalsString("currentTimeMillis"))
                && i.typeSymbol().descriptorString().equals("()J");
    }

    /** The class with every System clock call pointed here, or null when it has none. */
    static byte[] rewrite(final byte[] bytes) {
        final ClassFile cf = ClassFile.of();
        final ClassModel model = cf.parse(bytes);
        boolean any = false;
        for (final MethodModel m : model.methods()) {
            if (m.code().isPresent() && m.code().get().elementStream().anyMatch(WorkClock::isClockCall)) {
                any = true;
                break;
            }
        }
        if (!any) {
            return null;
        }
        REWRITTEN.incrementAndGet();
        return cf.transformClass(model, ClassTransform.transformingMethodBodies((code, e) -> {
            if (isClockCall(e)) {
                final InvokeInstruction i = (InvokeInstruction) e;
                code.invokestatic(SELF, i.name().stringValue(), MethodTypeDesc.of(ConstantDescs.CD_long));
            } else {
                code.with(e);
            }
        }));
    }
}
