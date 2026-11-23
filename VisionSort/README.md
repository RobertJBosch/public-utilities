# VisionSort Forge bridge

A small Java program that lets [VisionSort](https://github.com/RobertJBosch/VisionSort-releases),
a Magic: The Gathering collection app, play games with [Forge](https://github.com/Card-Forge/forge),
the open-source Magic rules engine. VisionSort uses it to test Commander decks against Forge's AI.

It is licensed under the **GNU GPL v3.0 or later** (see `LICENSE` at the root of this repository),
because it links Forge, which is GPL-3.0. VisionSort itself only starts it as a separate program
and talks to it over stdin / stdout.

## What it does

- Starts Forge headless. It doesn't use Forge's own `forge.view.Main`, so Forge's Sentry crash
  reporting is never switched on and nothing is sent anywhere.
- Reads one JSON request per line on stdin and writes one JSON reply per line on stdout. Everything
  Forge prints goes to stderr. See `Protocol.java` and `PlayCommand.java` for the messages.
- Re-seeds Forge's random generator before every game, so one process (Forge takes ~30 s to start)
  can play game after game, each repeatable from its seed.
- Gives each seat its own AI skill (`Skills`): one of Forge's profiles (Default, Cautious, Reckless,
  Experimental, Hard, Hardest), overrides of any of Forge's AI settings, and look-ahead off / on /
  max. `{"cmd":"profiles"}` lists every setting with Forge's own explanation and the values of
  Forge's profiles. A seat's look-ahead is bounded by its search counts, not by time, so its games
  repeat; Forge's own "max" mode is timed and isn't used ("max" here uses the same counts).
- Routes every AI decision through a hook (`DecisionHook`): it can log each one (what was asked,
  what was chosen) and let a policy change the answer. `MistakePolicy` gives a seat deliberate,
  always-legal mistakes at set rates, for VisionSort's "Novice" skill: skipped lands and spells,
  wrong targets, attackers held back or sent in, blocks skipped or added, other options, declined
  "may"s - each checked with Forge's own rules where it could be illegal. Without one, a seat plays
  exactly as Forge's own AI.
- `"first"` names the seat that plays first (otherwise Forge flips for it).
- `{"cmd":"resolve","names":[...]}` gives the name Forge knows each card by (a split card keeps
  "Fire // Ice"; double-faced, adventure and flip cards go by their front face), or null for a
  card this Forge version doesn't have - so a decklist can be checked before Forge silently drops
  a card from it.
- Records games (`"record": "steps"` or `"snapshots"`): one step per top-level decision, with
  Forge's own game events since the last step and a checksum of the game state as the decision
  was asked (`StateSnapshot`). `"stop_at": k` replays the same game to step k and returns the
  state there plus every checksum along the way. Games are a pure function of their seed, decks
  and settings, so the checksums match the recording exactly, in the same process or a new one.
- Seats a person against the AI (`{"cmd":"play_human"}`, see `HumanGame.java`). The human seat is
  Forge's own human player (`PlayerControllerHuman`), so Forge keeps every rule. Its GUI
  (`HumanSeat`) turns everything Forge asks a player into a decision message: priority with the
  actions you can take, paying mana, targets, attackers, blockers, confirmations, mulligans,
  choices, numbers and dividing combat damage. Each decision carries the game as that player may
  see it (`ViewState`). Answers come back as `{"cmd":"answer"}` and are applied the way a click
  on Forge's own table would be. An answer Forge won't take comes back "refused", with its
  reason. `concede`, `stops` (where you get priority) and `end` control the running game; one
  such game runs at a time. A practice option lets you redraw the opening hand for free before
  any mulligan. "ended" carries your answers, so the game can be replayed from its seed.
- Walks back and resumes games against a person (0.7.0): the same `play_human` request with
  `"replay"` (the recorded answers) plays the game again silently to where they run out, checking
  each decision's state checksum, then sends `resumed` (the state and the whole log) and goes on
  live. If the game went differently somewhere, it goes on live from there and says it wasn't exact.
- 0.7.1: dividing an amount among targets (damage, counters, shields) is a decision ("divide"), a
  phyrexian symbol can be paid with 2 life, and scry / surveil / "any number to the bottom" ask which
  cards and in what order.
- 0.8.0: play_human seats up to three AIs (a 4-seat table): you block only what attacks you, a player
  who loses is out while the others play on, and "ended" names the winner and how you went out.
- 0.9.0: `{"cmd":"watch"}` plays an AI-only game again and streams it like play_human (every hand
  shown), so a Test game can be stepped through on VisionSort's table.
- 0.9.1: a confirmation or choice about a card says which ("card", "card_name"), e.g. scry 1's top or
  bottom (the card is usually in the library, so not in the state).
- 0.9.2: fixes from an audit: a game stopped at its time limit is a draw (Forge called every player
  still in it a winner), and stays a draw if its thread throws while stopping; a dialog asked just as a
  game ends no longer waits for good; the next game starts only after the last one let go of its AI
  profiles; the combat-damage answer is checked like the divide answer; walking back is lighter.
- 0.9.3: "name a card" (Pithing Needle, Brain Pry, Meddling Mage...) is a "name_card" decision answered
  {"name": ...} and checked against the names Forge allows, instead of a list of every card Forge knows
  (~30,000); the combat-damage prompt names a token attacker.
- 0.9.4: a game against you says who plays first in its state ("first": the seat), from the coin toss on.

The hooked controller is generated from Forge's jar, since Forge's ~113 decision methods change
between releases. After upgrading Forge, regenerate it:

```
java -cp <forge>/forge-gui-desktop-<version>-jar-with-dependencies.jar tools/GenerateHookedController.java src/main/java/visionsort/bridge/HookedControllerAi.java
```

## Requirements

- Forge **2.0.16** (`forge-installer-2.0.16.tar.bz2` from Forge's GitHub releases), unpacked.
- Java **17 or newer** to run it; VisionSort uses Temurin 25 LTS. A JDK and Maven 3.9 to build it.

## Building

Forge's desktop jar isn't published to a Maven repository, so install it into your local one
first. `forge/forge-gui-desktop.pom` describes it; the pom inside the jar can't be used, because
it names a parent that isn't published.

```
mvn install:install-file -Dfile=<forge>/forge-gui-desktop-2.0.16-jar-with-dependencies.jar -DpomFile=forge/forge-gui-desktop.pom
mvn package
```

That gives `target/visionsort-forge-bridge-<version>.jar` (the version in `pom.xml`).

## Running

Put a `forge.profile.properties` in Forge's folder that points `userDir` and `cacheDir` at folders
of your choice. Otherwise Forge keeps its data under `%APPDATA%\Forge` (see Forge's
`forge.profile.properties.example`).

Then, from Forge's folder (Forge finds `res/` relative to the working directory). This is the
Windows form: on Linux / macOS use `:` between the jars and `\` to continue the line.

```
java -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 -XX:ActiveProcessorCount=1 -Xmx4096m -Dfile.encoding=UTF-8 ^
     -cp "forge-gui-desktop-2.0.16-jar-with-dependencies.jar;<path>/visionsort-forge-bridge-<version>.jar" ^
     visionsort.bridge.Main
```

**`-XX:hashCode=2` matters.** Without it, a seeded game repeats exactly only as the first game in a
fresh process: Forge's AI iterates some sets ordered by Java identity hashes, which depend on
everything the process did before. With it, every game is a pure function of its seed, decks and
settings. **`-XX:ActiveProcessorCount=1` matters too:** Forge's AI weighs attackers on a thread pool,
whose order otherwise varies from run to run. The `ready` message reports `"repeatable": true` when
both flags are set, and every game's reply says whether that game is. The bridge also lifts Forge's 5-second limit on each AI decision to the game's own
timeout, so a busy PC can't turn a slow decision into a pass.

Then send a request - one JSON object per line, for example:

```
{"cmd":"play","id":1,"seed":101,"format":"Commander","lookahead":"off","log":true,"players":[{"name":"A","deck":"X:/decks/a.dck","profile":"Default"},{"name":"B","deck":"X:/decks/b.dck","profile":"Hardest"}]}
```

A bad request gets `{"type":"error","message":...}` and the bridge carries on. A reply with
`"restart_required": true` (a game that wouldn't stop, or the JVM out of memory) is the last
one: the process exits after it, so start a new one.
