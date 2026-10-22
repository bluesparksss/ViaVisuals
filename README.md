# ViaVisuals [Beta] 👀

_ViaVersion/ViaBackwards visually perfected!_

Older clients normally see placeholders for blocks, items and mobs. That’s a pain almost every ViaVersion/ViaBackwards user recognizes!

ViaVisuals is a project that tries to make Minecraft on older clients (1.16+) see the world almost the same as the servers native version! It hooks into ViaVersion and ViaBackwards by removing its placeholders with actual animated models, textures and functionality just like vanilla Minecraft.

## Dependencies
- [ViaVersion](https://modrinth.com/plugin/viaversion)
- [ViaBackwards](https://modrinth.com/plugin/viabackwards)
- [PacketEvents](https://modrinth.com/plugin/packetevents)

I am not affiliated with any of these projects, but please show them some love!

## Version support
Client versions from 26.3 to 1.16 are supported. More support might be added in the future, but the inexistence of interaction entities, attributes and support for BetterModel make it a lot more challenging.
Currently 1.16.5-1.16 works, but looks visually less appealing with less smooth, laggier animations and worse rendering for blocks (most of this might get fixed in the future!)

Server versions from 26.3 to 1.20.4 are supported.

## Features
- Animated mob models on older clients
- Correct item textures on older clients
- Correct item usage on older clients
- New crafting recipe support
- Correct spear animations on older clients
- Automatic resource pack download on clients
- Optional retextured (and animated) mobs and new baby mobs support
- Locator bar support
- Armor trim support
- Fully correct hitboxes (interaction entities)
- New pearl physics fix
- New world height and deepslate layer support (1.16)

## Preformance
Because of heavy culling for some blocks and just resource pack retextures, blocks don't really lag your game at all. Entities consist of multiple body parts with BetterModel, but are relatively optimized to not lag your client too much. These limbs also get replaced with just 1 model when they are far enough from you, causing even less preformance drops.
In 1.16, support for larger world sizes and deepslate layer was added, but can cause a lag spike while switching through layers. This will probably stay this way for now and is a known issue.

## Use of generative AI
### (Please do not let this scare you off, and give it a chance!)
Generative AI was used in the making of this plugin, but still months of work and testing went into it. Because this is still a beta version, I will be open to feedback and try to patch as many glitches as possible.
