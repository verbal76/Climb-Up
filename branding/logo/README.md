# Upwardly logo (title + tagline, transparent background)

The game's name and tagline exactly as the title screen draws them, layers and colours included, with nothing
behind them. Built from the game's own pixel font by `tools/make_logo.py`.

| File | Use |
|---|---|
| `upwardly-logo.svg` / `.pdf` / `.png` | UPWARDLY + "TEN TOWERS. ONE CLOCK." |
| `upwardly-logo-alt-tagline.*` | UPWARDLY + "TRAMPOLINES, CRABS AND CANNONS. YOU'RE WELCOME." (the other title-screen line) |
| `upwardly-title-only.*` | UPWARDLY alone |

- **SVG and PDF are vector:** they scale to any size, huge or tiny, with no loss. In Photoshop use
  **File > Place Embedded** and pick the `.pdf` or `.svg`; it arrives as a vector smart object you can resize
  forever without blur.
- **PNG** is 20 times the game's size (the main logo is 14,680 x 3,460 pixels) with a transparent background, for
  apps that do not take vectors. Shrinking it is clean; for anything bigger, use the SVG or PDF.

Layers, bottom to top, as in the game: faded fringe (black 60 %), thin brown edge `#591F05`, dark shadow `#331200`
at 90 %, gold face `#FFBD29`; tagline cream `#FFFAEB` over a black 55 % shadow, 56 game pixels below the title.
