# Buteco Play

Client-side Fabric mod for **Minecraft 26.1.2** and **Fabric Loader 0.19.2 or later**.

It removes these title-screen elements:

- Singleplayer
- Multiplayer
- Minecraft Realms
- Realms diamond and newspaper notification symbols

It keeps Mod Menu's **Mods** button and adds one full-width **Play BUTECO :D** button immediately above it. Pressing the button connects directly to:

```text
buteco.qd.je
```

## Version 1.0.5

This update:

- Disables the separate Realms notification overlay using a client mixin, removing the diamond and newspaper symbols.
- Gives `:D` the same final purple color used by the `O` in `BUTECO`.
- Keeps Fabric Loader compatibility at version 0.19.2 or later.

## Build with GitHub Actions

Upload the contents of this project folder to the root of a GitHub repository. Open **Actions**, choose **Build**, and run the workflow. The compiled mod is included in the downloadable `buteco-play` artifact.

Install this file from the artifact:

```text
buteco-play-1.0.5.jar
```

Do not install the `-sources.jar` file.

## Install

Put the built JAR in the client's `mods` folder together with Fabric API and Mod Menu for Minecraft 26.1.2.

This mod is client-side only; it does not need to be installed on the server.

## Change the server

Edit these constants in `ButecoPlayClient.java`:

```java
private static final String SERVER_NAME = "Buteco";
private static final String SERVER_ADDRESS = "buteco.qd.je";
```
