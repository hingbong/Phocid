# Phocid icons

Two adaptive icons that belong to the same family but use different colours:

- **Player** — a rose-pink five-petal sakura with a cream record centre and a note.
- **Agent bridge** — a mint-green six-node graph with a warm gold hub.

The shared language is a 108 × 108 canvas, a dark radial background, artwork
inside the 66 dp safe circle, and a monochrome layer for themed icons.

## Files

| File | Purpose |
| --- | --- |
| `player_background.svg`, `player_foreground.svg`, `player_monochrome.svg` | Editable player artwork |
| `bridge_background.svg`, `bridge_foreground.svg`, `bridge_monochrome.svg` | Editable bridge artwork |
| `player-512.png`, `bridge-512.png` | Rendered previews |
| `preview.jpg` | Both icons at launcher sizes and as monochrome |

The Android resources are checked in as VectorDrawables:

- `app/src/main/res/drawable/ic_player_*.xml`
- `app/src/main/res/mipmap-anydpi/ic_launcher.xml`
- `agent-bridge/src/main/res/drawable/ic_bridge_*.xml`
- `agent-bridge/src/main/res/mipmap-anydpi/ic_bridge.xml`

The monochrome layers are stored as a boolean-simplified single path with
`fillType="evenOdd"` so the note (player) and centre hole (bridge) stay
transparent when Android tints the icon.

## Palette

| Use | Player | Bridge |
| --- | --- | --- |
| Background centre | `#552446` | `#12473F` |
| Background edge | `#1A0A17` | `#04161A` |
| Highlight | `#FFF2F7` | `#B4F5E6` |
| Mid | `#FFB6D4` | `#59D6BB` |
| Shadow | `#E2679F` | `#1F9E86` |
| Core | `#FFFDF8` → `#F2DCC4` | `#FFF3D2` → `#DFA23F` |

All artwork is licensed under CC BY-SA 4.0, matching the project's app icon
license.
