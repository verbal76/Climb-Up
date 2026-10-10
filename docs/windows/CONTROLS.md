# UPWARDLY (Windows) - controls

Keyboard, mouse and controller are all active at the same time; nothing has to be selected. Button prompts follow whichever you used last.

## Defaults
| action | keyboard / mouse | Xbox-style | PlayStation-style |
|---|---|---|---|
| Move left / right | A, Left / D, Right | Left stick, D-pad | Left stick, D-pad |
| Climb up / down (ropes, cables, drop) | W, Up / S, Down | stick up/down, D-pad | stick up/down, D-pad |
| Jump | Space | A | Cross |
| Swing club | X, J | X | Square |
| Pause | Escape, P | Menu (Start) | Options |
| Menu up/down/left/right | W/S/A/D or arrows | D-pad / left stick | D-pad / left stick |
| Confirm | Enter, Space | A | Cross |
| Back / cancel | Escape | B | Circle |
| Mouse | left click selects/activates menu items, hover focuses, wheel moves through menus. The mouse never steers the hero. | | |
| Window | F11 or Alt+Enter toggles fullscreen | | |

In menus the arrow keys, Enter and Escape (and the D-pad, A and B on a controller) always work, even if every menu binding has been changed: this is the recovery path.

## Settings > CONTROLS
INPUT DEVICE (AUTO DETECT / KEYBOARD + MOUSE / CONTROLLER: fixes the *prompts*, never disables a device), CONNECTED CONTROLLER (name; select to switch when several are connected), KEYBOARD + MOUSE BINDINGS, CONTROLLER BINDINGS, CONTROLLER DEAD ZONE (5-60%, default 25%), RESTORE DEFAULTS (for the device in use, asks twice). Also reachable from the pause menu.

## Rebinding
Select a slot -> "PRESS A KEY..." (or push a stick / press a button) -> proposed binding is shown with any conflict -> CONFIRM / TRY AGAIN / CANCEL. Cancelling or unplugging the controller changes nothing. A conflict moves the input to the new action only if the other action keeps at least one input. Up to three bindings per action; the last binding of an action cannot be removed. Left mouse button and F11 are reserved. Controller stick directions are bound by axis and direction (e.g. "L STICK LEFT"). Settings are written atomically (temp file, then rename; the previous file is kept as `.bak`).

## Controllers
Detected automatically (SDL game-controller database via gdx-controllers/jamepad): Xbox, PlayStation, Switch Pro and generic XInput/DInput pads, plus Steam Input virtual controllers. Plug in or pull out at any time: the run is never paused or reset. With several controllers the one used last is active; they are never combined. Stick drift cannot switch prompts (a deliberate push of at least 65% is needed) and prompt switches are debounced.
