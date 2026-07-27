# Buteco Play

Client-side Fabric mod for **Minecraft 26.2** and **Fabric Loader 0.19.3 or later**.

## What it changes

- Removes Singleplayer, Multiplayer, and Minecraft Realms from the title screen.
- Keeps Mod Menu's **Mods** button.
- Adds one full-width **Play BUTECO :D** button above Mods.
- Connects directly to `buteco.qd.je`.
- Removes the Realms diamond and newspaper notification symbols.
- Returns to the customized title screen after disconnecting.
- Removes Minecraft 26.2's Friends button from the title screen and pause menu.
- Blocks the Friends List overlay, including the default `O` key route.
- Replaces Online Options with a minimal screen that keeps only Realms **News & Invites**, removing the entire Friends List section.

Minecraft 26.2 final does not include the experimental peer-to-peer world connection feature; Mojang removed it during the pre-release phase. This mod additionally removes access to the remaining Friends List interface.

## Build with GitHub Actions

Upload the contents of this project folder to the root of your GitHub repository. Open **Actions**, choose **Build**, and run the workflow. The compiled mod is included in the downloadable `buteco-play` artifact.

Install this file from the artifact:

```text
buteco-play-1.1.0.jar
```

Do not install the `-sources.jar` file.

## Install

Put the built JAR in the client's `mods` folder together with Fabric API and Mod Menu for Minecraft 26.2.

This mod is client-side only; it does not need to be installed on the server.

## Change the server

Edit these constants in `ButecoPlayClient.java`:

```java
private static final String SERVER_NAME = "Buteco";
private static final String SERVER_ADDRESS = "buteco.qd.je";
```
