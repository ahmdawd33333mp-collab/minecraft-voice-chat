---
title: minecraft-voice-relay
sdk: docker
app_port: 7860
pinned: false
---

# Minecraft proximity voice relay

Bun WebSocket relay for the Minecraft Bedrock proximity voice pilot.
HTTP GET / returns `minecraft-voice-relay v1 ok` (used by the heartbeat monitor).
No secrets, no database, in-memory groups only.
