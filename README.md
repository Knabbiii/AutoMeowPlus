![AutoMeow+ Banner](.github/assets/automeowplus-banner.png)

<div align="center">

[![Modrinth Downloads](https://img.shields.io/modrinth/dt/automeow+?style=for-the-badge&logo=modrinth&color=f6a0d3)](https://modrinth.com/mod/automeow+)
[![CodeFactor](https://img.shields.io/codefactor/grade/github/knabbiii/AutoMeowPlus?style=for-the-badge&logo=codefactor&color=f6a0d3&label=Code%20Quality)](https://www.codefactor.io/repository/github/knabbiii/AutoMeowPlus)
[![License: MIT](https://img.shields.io/github/license/Knabbiii/AutoMeowPlus?color=f6a0d3&label=License&style=for-the-badge&logo=github)](https://opensource.org/licenses/MIT)
[![GitHub release](https://img.shields.io/github/v/release/knabbiii/AutoMeowPlus?style=for-the-badge&label=Release&color=d004f7&logo=github)](https://github.com/Knabbiii/AutoMeowPlus/releases)

</div>

---

## Overview

A tiny mod to meow back to your fellow catsies in a server chat :3 
This client-side mod automatically responds when someone says "meow" in chat. Complete with **customizable replies**, **sound effects**, and **heart particles**. 

---

## Features

| Feature | Description |
|---------|-------------|
| **Smart Auto-Reply** | Automatically responds when "meow" is detected in chat |
| **Customizable Replies** | Add, remove, or reset reply variations (mrow, purr, nya, etc.) |
| **Cat Sound Effects** | Plays authentic cat meows with randomized pitch and volume |
| **Heart Particles** | Spawns heart particles around players who meow |
| **Hypixel Channel Detection** | Works in guild chat, party chat, coop chat, and all chat on Hypixel |
| **Anti-Spam Protection** | Message cooldowns and requirements prevent chat flooding |
| **Chroma Support** | Aaron-mod integration for animated rainbow badge |
| **Config GUI** | Full Mod Menu integration with Cloth Config screens |
| **Achievement System** | Track your meow count with milestone celebrations |

### Why Choose AutoMeow+?
- **Highly configurable** with in-game commands and GUI
- **Lightweight** client-side mod - no server installation needed
- **Smart detection** prevents echo loops and spam
- **Feature-rich** with sounds, particles, and custom replies
- **Privacy-focused** with local config storage

---

## Configuration

AutoMeow+ offers extensive in-game configuration:
- **Message requirements** - Set how many messages you must send before auto-replying (0-10)
- **Time cooldown** - Configure quiet period after sending messages (0-30 seconds)
- **Custom replies** - Add unlimited custom meow variations
- **Sound effects** - Toggle cat sounds with jitter for natural variation
- **Heart particles** - Enable/disable heart effects
- **Chroma badge** - Animated rainbow [AutoMeow+] prefix (requires Aaron-mod)

All settings persist between sessions and are stored locally in `config/automeow.json`.

---

## Commands & Features

### Commands
- `/automeow` - Display current status
- `/automeow toggle` - Enable/disable the mod
- `/automeow on` / `/automeow off` - Explicit on/off
- `/automeow chroma` - Toggle chroma badge (requires Aaron-mod)
- `/automeow sound [on|off]` - Toggle cat sound effects
- `/automeow hearts [on|off]` - Toggle heart particle effects
- `/automeow messages <0-10>` - Set message requirement
- `/automeow cooldown <0-30>` - Set cooldown in seconds
- `/automeow addreply <text>` - Add custom reply (max 50 characters)
- `/automeow removereply <text>` - Remove a custom reply
- `/automeow listreplies` - View all current replies
- `/automeow resetreplies` - Reset to default replies
- `/automeow meows` - View your total meow count

### Built-in Reply Variations
- mroww, purr, meowwwwww, meow :3, mrow, mrow :3
- purrr :3, nya :3, mraow, mreow, mew mew
- meowmeow, ^._.^ ... and more!

---

## Server Compatibility

AutoMeow+ is designed to work on any Minecraft server:
- **Hypixel** - Automatically detects guild, party, and coop chat
- **Vanilla servers** - Works in standard chat
- **Modded servers** - Compatible with other client mods
- **No server-side installation required** - Pure client-side mod

---

## Dependencies

### Required
- **Fabric Loader** ≥0.17.3
- **Fabric API** (any version)
- **Minecraft** 1.21.10

### Optional (for enhanced features)
- **Mod Menu** - For config GUI integration
- **Cloth Config** - For visual settings screen
- **Aaron-mod** - For chroma badge animation

---

## Credits

**Developer:** [Knabbiii](https://github.com/knabbiii)

**Original Concept:** [Rhynohowl](https://github.com/Rhynohowl)

Enhanced with customizable replies, achievement tracking, improved channel detection, sound effects, particle effects, and comprehensive configuration options.

---

<div align="center">

## 🌟 Join KnabbiiiCraft - CraftAttack-Style Server!
[![KnabbiiiCraft Discord](https://img.shields.io/discord/1306648324685828157?style=for-the-badge&logo=discord&logoColor=white&label=KnabbiiiCraft&color=d004f7)](https://discord.gg/ZaV7SzKEY6)

**Experience AutoMeow+ in action on our CraftAttack-inspired server!**

*Where this mod was born and tested - join our community!*

</div>

---

<div align="center">

*Made with ❤️ for cat lovers and the Minecraft community* 🐱

</div>
