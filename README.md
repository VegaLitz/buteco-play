# Buteco Play

Client-side Fabric mod for **Minecraft 26.2** and **Fabric Loader 0.19.3 or later**.

## What it changes

- Removes Singleplayer, Multiplayer, and Minecraft Realms from the title screen.
- Replaces the old text Play button with the supplied **BUTECO logo** inside one clickable button.
- Moves **Accessibility**, **Language**, and **Mods** into a vertical column to the left of the logo button.
- Connects directly to the server address stored in `config/buteco.txt` (default: `buteco.qd.je`).
- Removes the Realms diamond and newspaper notification symbols.
- Returns to the customized title screen after disconnecting.
- Removes Minecraft 26.2's Friends button from the title screen and pause menu.
- Blocks the Friends List overlay, including the default `O` key route.
- Disables the **Online...** button in Options.

## Build with GitHub Actions

Upload the contents of this project folder to the root of your GitHub repository. Open **Actions**, choose **Build**, and run the workflow. The compiled mod is included in the downloadable `buteco-play` artifact.

Install this file from the artifact:

```text
buteco-play-1.1.10.jar
```

Do not install the `-sources.jar` file.

## Install

Put the built JAR in the client's `mods` folder together with Fabric API and Mod Menu for Minecraft 26.2.

This mod is client-side only; it does not need to be installed on the server.

## Change the server

Launch Minecraft once with the mod installed. It creates:

```text
.minecraft/config/buteco.txt
```

The file contains one address by default:

```text
buteco.qd.je
```

Replace that line with another hostname or `hostname:port`. The file is read whenever the **BUTECO logo button** is pressed, so Minecraft does not need to be restarted after editing it. Blank lines and lines beginning with `#` are ignored. If the file is missing, empty, or unreadable, the mod falls back to `buteco.qd.je`.


## v1.1.5

- Sharper Play BUTECO logo using a higher-quality resample of the provided artwork.
- Clears side-button labels and shrinks icon widgets so their text does not render beneath the logo.


## v1.1.6

- Removes the hover tooltips from the Mods, Accessibility, and Language icon buttons so their text cannot render underneath the BUTECO artwork.


## v1.1.7

- Restores hover text for Mods, Accessibility, and Language.
- Renders each tooltip manually after the BUTECO logo, so the text and tooltip background appear on top of the image instead of underneath it.


## v1.1.8

- Updated the mod description.
- Changed the credited author from Victor to VegaLitz.


## v1.1.9

- Moves SkinShuffle's Skin Presets button onto the same row as Options and Quit Game.
- Centres the entire bottom row horizontally.
- Aligns and centres the upper icon/logo group to the same horizontal bounds.


## v1.1.10

- Keeps Skin Presets in the centred bottom row while ending the upper BUTECO button at the right edge of Quit Game.
- Moves the Mods, Accessibility, and Language tooltip close to the pointer, slightly below and to the right.
- Replaces the purple tooltip outline with a stepped white Minecraft-style border inset by one pixel.


## v1.1.11

- Reorders the bottom row to Options, Skin Presets, Quit Game.
- Keeps the complete row centred.
- Adds a SkinShuffle compatibility adjustment that makes the title-screen skin preview slightly smaller, lowers it toward the Skin Presets button, and follows the button when it moves.
