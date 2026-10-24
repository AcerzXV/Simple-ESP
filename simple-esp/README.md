# Simple ESP

A small client-side Fabric mod for Minecraft 26.3. Highlights players and any
blocks / entities you pick, through walls, with optional tracers.

Meant for anarchy servers where client mods are fair game. On servers with
rules, this will get you banned.

## Use

| Key | Action |
| --- | --- |
| Right Shift | Open the ESP menu (rebindable in Controls) |
| Backslash | Toggle ESP on/off (rebindable in Controls) |

In the menu:

* **ESP / Players / Tracers** - master switch, player boxes, lines to targets
* **Block tracers** - also draw lines to highlighted blocks
* **Radius** - how many chunks around you are scanned for your blocks, 2 to 32 (only chunks the server has sent you can be scanned, so it is capped by the server view distance)
* **Opacity** - fill opacity of the boxes
* **Blocks / Entities tabs** - search and click any entry to add/remove it

Settings are saved to `config/simple-esp.json`.

## Build

    ./gradlew build

The jar lands in `build/libs/` (use `simple-esp-1.0.0.jar`, not the `-sources` one).
Requires JDK 25. Drop the jar in your `mods` folder with Fabric Loader and Fabric API.

## License

CC0.
