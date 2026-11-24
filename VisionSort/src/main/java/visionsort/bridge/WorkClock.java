// Bosch SoftWorks LLC - VisionSort's Forge bridge. GPL-3.0-or-later, as Forge.
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
 * when the AI asks it the time, by STEP each time - so it never comes near any of those limits, and the search is
 * bounded by its counts alone: the same on every PC, every run. The cost: a slow PC waits longer on a decision.
 *
 * Only forge/ai is rewritten: the bridge's own timeouts (a game's time limit, a stuck game) keep real time.
 */
public final class WorkClock {
    /** A nanosecond per question: 10 s of the AI's budget would take ten billion of them. */
    static final long STEP = 1;
    private static final AtomicLong NOW = new AtomicLong(1_000_000_000L);
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
