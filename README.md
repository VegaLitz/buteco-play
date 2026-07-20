# Buteco Play

Client-side Fabric mod for **Minecraft 26.1.2**.

It removes these title-screen buttons:

- Singleplayer
- Multiplayer
- Minecraft Realms

It adds one full-width **PLAY** button immediately above the existing **Mods** button. Pressing PLAY connects directly to:

```text
buteco.qd.je
```

## Build locally

Requirements:

- JDK 25
- Gradle 9.5.1

Run:

```bash
gradle build
```

The mod JAR will be in `build/libs/`.

## Build without installing Gradle

Upload this project to a GitHub repository and run the included **Build** workflow under the repository's **Actions** tab. Download the `buteco-play` artifact after the workflow completes.

## Install

Put the built JAR in the client's `mods` folder together with Fabric API for Minecraft 26.1.2.

This mod is client-side only; it does not need to be installed on the server.

## Change the server

Edit these constants in `ButecoPlayClient.java`:

```java
private static final String SERVER_NAME = "Buteco";
private static final String SERVER_ADDRESS = "buteco.qd.je";
```
