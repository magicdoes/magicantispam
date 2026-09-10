# MagicAntiSpam

A lightweight Paper anti-spam plugin for MagicSMP.

## Features

- Chat cooldown between messages
- Detects repeated and similar messages
- Shows warnings in the **action bar** (above the hotbar/inventory)
- Plays the Minecraft villager "no" sound when a message is blocked
- Ignores capitalization, spaces, and punctuation when comparing messages
- Configurable cooldown, similarity threshold, messages, and sound
- Permission bypass for staff

## Requirements

- Paper 1.21.11
- Java 21+

## Build

### GitHub Actions
Push the project to GitHub. The included workflow automatically builds the plugin.

Go to:

`Actions -> Build MagicAntiSpam -> latest run -> Artifacts`

Download `MagicAntiSpam`.

### Local build

```bash
./gradlew build
```

The jar will be created in:

```text
build/libs/MagicAntiSpam-1.0.0.jar
```

## Permissions

```text
magicantispam.bypass
```

Players with this permission bypass all anti-spam checks.

OPs also bypass by default.

## Configuration

Edit:

```text
plugins/MagicAntiSpam/config.yml
```

Then restart the server or use:

```text
/magicantispam reload
```

Permission for reload:

```text
magicantispam.reload
```
