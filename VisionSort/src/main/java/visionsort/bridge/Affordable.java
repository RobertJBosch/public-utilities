// Bosch SoftWorks LLC - VisionSort's Forge bridge. GPL-3.0-or-later, as Forge.
package visionsort.bridge;

import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostExile;
import forge.game.cost.CostSacrifice;
import forge.game.keyword.Keyword;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.InputPayMana;
import forge.util.MyRandom;

import java.util.Random;

/**
 * Can a priority action be paid for right now? Three answers, and only NO is safe to leave out:
 *
 *  YES     Forge's own check finds a payment (ComputerUtilMana.canPayManaCost - what Forge's GUI uses to
 *          highlight playable cards, AvailableActions).
 *  NO      proven: even every mana source the payment would offer, each counted at its most, can't cover the
 *          cost (its total, or the shards of one color). Nothing is guessed: whatever could bend the count
 *          (Convoke, Delve, Improvise, Waterbend, Emerge, Offering, Assist, mana doublers, "spend as though any
 *          color", a source that can be used again and again) makes the answer UNSURE instead.
 *  UNSURE  neither. Forge's check is frugal by design - it won't sacrifice a source for a spell it thinks isn't
 *          worth it (ManaSacrifice.worthIt), won't pay Phyrexian mana with life for some cards, keeps Black Lotus
 *          for later - so its "no" alone never hides a move.
 *
 * Forge's check runs as Forge's GUI runs it: under a throwaway AI controller (no prompts), and here also on a
 * throwaway random generator, since the AI's payment logic rolls dice (RESERVE_MANA_FOR_MAIN2_CHANCE): the game's
 * own sequence must not move, or a game would differ from the same game played without the check.
 */
final class Affordable {
    enum Verdict { YES, NO, UNSURE }

    private static final ZoneType[] MANA_ZONES = {ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard,
            ZoneType.Exile, ZoneType.Command};
    private static final String[] COLORS = {"W", "U", "B", "R", "G"};

    private Affordable() {
    }

    /** No legal choice for a target it must have: casting it would only be cancelled (a rules check, no judgement). */
    static boolean targetable(final SpellAbility sa) {
        return ComputerUtilAbility.isFullyTargetable(sa);
    }

    static Verdict of(final SpellAbility sa, final Player me) {
        final Cost cost = sa.getPayCosts();
        if (cost == null || !cost.hasManaCost()) {
            return Verdict.YES;                        // the other costs: canPlay() has checked them already
        }
        final boolean[] yes = {false};
        final ManaCostBeingPaid[] need = {null};
        final Random real = MyRandom.getRandom();
        MyRandom.setRandom(new Random(0));
        try {
            me.runWithController(() -> {
                yes[0] = ComputerUtilMana.canPayManaCost(sa, me, 0, false);
                need[0] = ComputerUtilMana.calculateManaCost(cost, sa, me, true, 0, false);
            }, new PlayerControllerAi(me.getGame(), me, me.getOriginalLobbyPlayer()));
        } catch (RuntimeException e) {
            return Verdict.UNSURE;                     // a card Forge's check can't handle: offer it
        } finally {
            MyRandom.setRandom(real);
        }
        if (yes[0]) {
            return Verdict.YES;
        }
        return provablyShort(sa, me, need[0]) ? Verdict.NO : Verdict.UNSURE;
    }

    /** For the measurement: is the cost provably out of reach, whatever Forge's check says? (Never true with YES.) */
    static boolean proven(final SpellAbility sa, final Player me) {
        final Cost cost = sa.getPayCosts();
        if (cost == null || !cost.hasManaCost()) {
            return false;
        }
        final ManaCostBeingPaid[] need = {null};
        final Random real = MyRandom.getRandom();
        MyRandom.setRandom(new Random(0));
        try {
            me.runWithController(() -> need[0] = ComputerUtilMana.calculateManaCost(cost, sa, me, true, 0, false),
                    new PlayerControllerAi(me.getGame(), me, me.getOriginalLobbyPlayer()));
        } catch (RuntimeException e) {
            return false;
        } finally {
            MyRandom.setRandom(real);
        }
        return provablyShort(sa, me, need[0]);
    }

    private static boolean provablyShort(final SpellAbility sa, final Player me, final ManaCostBeingPaid need) {
        if (need == null || bendsTheCount(sa, me)) {
            return false;
        }
        // what the cost asks for at least: Phyrexian shards can be paid with life, hybrid and 2/W shards with
        // either half, X is at its least (calculateManaCost takes X as announced: nothing yet)
        int total = need.getGenericManaAmount();
        final int[] colored = new int[COLORS.length];
        for (final ManaCostShard s : need.getUnpaidShards()) {
            if (s == ManaCostShard.GENERIC || s.isPhyrexian()) {
                continue;
            }
            total += 1;
            if (s.isMonoColor() && !s.isOr2Generic() && !s.isHybrid()) {
                for (int i = 0; i < COLORS.length; i++) {
                    if (s.canBePaidWithManaOfColor(forge.card.MagicColor.fromName(COLORS[i]))) {
                        colored[i]++;
                    }
                }
            }
        }
        if (total == 0) {
            return false;
        }
        // what every source could give at most: the mana already floating, and each source's mana abilities
        int supply = me.getManaPool().totalMana();
        final int[] supplyOf = new int[COLORS.length];
        for (int i = 0; i < COLORS.length; i++) {
            supplyOf[i] = me.getManaPool().getAmountOfColor(forge.card.MagicColor.fromName(COLORS[i]));
        }
        for (final ZoneType z : MANA_ZONES) {
            for (final Card c : me.getCardsIn(z)) {
                for (final SpellAbility ma : InputPayMana.getAllManaAbilities(c, me)) {
                    if (!ma.canPlay()) {
                        continue;
                    }
                    final int most = most(ma);
                    if (most < 0) {
                        return false;                  // no upper bound to this source: can't prove anything
                    }
                    supply += most;
                    final AbilityManaPart mp = ma.getManaPart();
                    for (int i = 0; i < COLORS.length; i++) {
                        if (mp == null || mp.isAnyMana() || mp.isSpecialMana() || mp.canProduce(COLORS[i], sa)) {
                            supplyOf[i] += most;
                        }
                    }
                }
            }
        }
        if (supply < total) {
            return true;
        }
        for (int i = 0; i < COLORS.length; i++) {
            if (supplyOf[i] < colored[i]) {
                return true;
            }
        }
        return false;
    }

    /** The most mana one use of this ability makes, or -1 when that isn't a fixed number or it can be used again. */
    private static int most(final SpellAbility ma) {
        final Cost c = ma.getPayCosts();
        final boolean once = c != null && (c.hasTapCost() || c.hasSpecificCostType(CostSacrifice.class)
                || c.hasSpecificCostType(CostExile.class));
        if (!once) {
            return -1;                                 // a filter or a pump-mana ability: no bound from here
        }
        final AbilityManaPart mp = ma.getManaPart();
        if (mp == null) {
            return -1;
        }
        int amount = 1;
        if (ma.hasParam("Amount")) {
            try {
                amount = Integer.parseInt(ma.getParam("Amount").trim());
            } catch (NumberFormatException e) {
                return -1;                             // "Amount$ X": counts something on the battlefield
            }
        }
        final String produced = mp.getOrigProduced();
        if (produced == null || produced.isBlank()) {
            return -1;
        }
        int symbols = 0;
        for (final String part : produced.trim().split("\\s+")) {
            if (part.equals("Combo") || part.equals("Any") || part.equals("Chosen")) {
                symbols = Math.max(symbols, 1);        // "Combo W U": one mana of those colors
                break;
            }
            symbols++;
        }
        return amount * Math.max(1, symbols);
    }

    /** Anything that changes what the cost or the sources come to in a way the count above can't see. */
    private static boolean bendsTheCount(final SpellAbility sa, final Player me) {
        final Card host = sa.getHostCard();
        if (sa.isSpell() && (host.hasKeyword(Keyword.CONVOKE) || host.hasKeyword(Keyword.DELVE)
                || host.hasKeyword(Keyword.IMPROVISE) || host.hasKeyword(Keyword.ASSIST)
                || sa.isEmerge() || sa.isOffering())) {
            return true;
        }
        if (sa.hasParam("TapCreaturesForMana") || (sa.getMaxWaterbend() != null && sa.getMaxWaterbend() > 0)) {
            return true;
        }
        if (me.hasKeyword("PayLifeInsteadOf:B")) {
            return true;
        }
        final Game game = me.getGame();
        for (final Card c : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (final Trigger t : c.getTriggers()) {
                if (t.getMode() == TriggerType.TapsForMana || t.getMode() == TriggerType.ManaAdded) {
                    return true;                       // Mana Reflection's cousins: a tap makes more than it says
                }
            }
            for (final ReplacementEffect r : c.getReplacementEffects()) {
                if (r.getMode() == ReplacementType.ProduceMana) {
                    return true;
                }
            }
            for (final StaticAbility st : c.getStaticAbilities()) {
                if (st.checkMode(StaticAbilityMode.ManaConvert)) {
                    return true;                       // spend mana as though it were any color
                }
            }
        }
        return false;
    }
}
