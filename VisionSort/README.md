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
- Routes every AI decision through a hook (`DecisionHook`): it can log each one (what was asked,
  what was chosen) and let a policy change the answer. `MistakePolicy` gives a seat deliberate,
  always-legal mistakes at set rates, for VisionSort's "Novice" skill. Without one, a seat plays
  exactly as Forge's own AI.

The hooked controller is generated from Forge's jar, since Forge's ~115 decision methods change
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

That gives `target/visionsort-forge-bridge-0.2.0.jar`.

## Running

From Forge's folder (Forge finds `res/` relative to the working directory):

```
java -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 -Xmx4096m -Dfile.encoding=UTF-8 ^
     -cp "forge-gui-desktop-2.0.16-jar-with-dependencies.jar;<path>/visionsort-forge-bridge-0.2.0.jar" ^
     visionsort.bridge.Main
```

**`-XX:hashCode=2` matters.** Without it, a seeded game repeats exactly only as the first game in a
fresh process: Forge's AI iterates some sets ordered by Java identity hashes, which depend on
everything the process did before. With it, every game is a pure function of its seed, decks and
settings. The `ready` message reports `"repeatable": true` when the flag is set.

Then send for example:

```
{"cmd":"play","id":1,"seed":101,"format":"Commander","lookahead":"off","log":true,
 "players":[{"name":"A","deck":"X:/decks/a.dck","profile":"Default"},
            {"name":"B","deck":"X:/decks/b.dck","profile":"Hardest"}]}
```
