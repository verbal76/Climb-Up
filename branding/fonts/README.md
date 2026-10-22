# Upwardly font pack

The game's own lettering as installable fonts, traced from the 5 x 7 pixel table the game draws every letter
from (`core/.../ui/PixelFont.java`), so they match the game pixel for pixel.

## Start here: the colour fonts (one text layer, every layer built in)

| Font | What you get when you type |
|---|---|
| **Upwardly Main Color** (`UpwardlyMain-Color.ttf`) | the title look: gold face, thin brown edge, dark shadow and the faded fringe, stacked, offset and coloured exactly like the title screen |
| **Upwardly Minor Color** (`UpwardlyMinor-Color.ttf`) | the line under the title and all other in-game text: cream face over its faded black shadow |

Type in them like any font. Nothing to stack, offset or colour: the colours and the transparency are inside the
font. The text colour you pick in Photoshop is ignored (the font brings its own colours).

- **Photoshop:** CC 2017 or later shows colour fonts. Use **120 px** (or 240, 360 px) for Main Color to get every
  layer on exact pixels, as the game draws it; Minor Color is exact at any multiple of 8 px (24 or 32 px is the
  game's size). Other sizes still work, the thin edge and the fringe just land between pixels and look softer.
- **Windows 10 / 11:** WordPad, browsers and most apps show the colours. Apps that cannot show colour fonts show
  just the face in your text colour.

## The single-layer fonts (if you want to recolour or rearrange a layer)

| Family | Files (layers, bottom to top) |
|---|---|
| **Upwardly Main** | `UpwardlyMain-Halo.ttf` (faded fringe, black 60 %), `UpwardlyMain-Edge.ttf` (brown `#591F05`), `UpwardlyMain-Shadow.ttf` (`#331200` at 90 %), `UpwardlyMain-Regular.ttf` (gold `#FFBD29`); `UpwardlyMain-Solid.ttf` = face + shadow as one shape |
| **Upwardly Minor** | `UpwardlyMinor-Shadow.ttf` (black 55 %), `UpwardlyMinor-Regular.ttf` (cream `#FFFAEB`); `UpwardlyMinor-Solid.ttf` |

Each layer font already carries its own offset, so same text, same position, one layer per font, and the colours
above rebuild the title by hand. Use anti-aliasing **None** in Photoshop for hard pixels.

## Install

- **Windows:** select all the `.ttf` files, right-click, **Install for all users**. Restart Photoshop if it was open.
- **macOS:** open each file in Font Book and click Install. **Linux:** copy to `~/.local/share/fonts`, run `fc-cache`.

## Characters

Capitals only, like the game: lowercase letters show as capitals. Monospaced. A to Z, 0 to 9, space, and
`! " # $ % & ' ( ) * + , - . / : ; < = > ? @ [ \ ] ^ _ ` { | } ~` plus `• … © ® ™ ° × ÷ ± £ € ¥ ¢ ¿ ¡`. Curly quotes,
en and em dashes and the minus sign use the straight marks. No accented letters. The game itself only has the letters,
digits and `. , : ! ? - + / ( ) % ' = < > _ &`; the rest were drawn on the same grid in the same style.

The speech bubble, the pop-ups ("HUP!", "NICE CATCH!"), the tips and the HUD all use this same lettering: Minor
with its shadow (the speech bubble draws it without the shadow, dark navy `#14142A` on white).

## Checked

`tools/make_fonts.py --check --chrome ...` renders every character of every file: through FreeType with hinting
off, the colour fonts in colour; through Chromium (both colour tables: the COLR table Windows and browsers use and
the SVG table Photoshop uses); and through the OpenType Sanitizer. Every render matched the game's pixels and
colours exactly. `title-vs-fonts.png` puts the game's own title next to the font version.

Original work by Hot Attic Games, copyright Hot Attic Games. No third-party font is involved.
