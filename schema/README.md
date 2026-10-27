# TuneMind — Shared Schema

This directory contains distinct schemas for local Android capture and a future backend-ingestion payload. They are not interchangeable.

## Files

| File | Description |
|---|---|
| `listening-event.schema.json` | JSON Schema (draft-07) for a raw `ListeningEvent` as sent from Android to the backend |
| `raw-listening-observation-v1.schema.json` | Versioned local Android Room payload; the current Android model and codec contract |

## Principles

- This schema describes **raw observations only** — what was directly observed by the Android device.
- No inferences (skip probability, fatigue, auditory anchors) are stored in a `ListeningEvent`.
- Derived analytics live in separate backend collections with explicit confidence fields.
- `listening-event.schema.json` is a future server-ingestion contract. It currently includes account/device-facing fields and server receipt time, and is not used to encode local Room rows.
- `raw-listening-observation-v1.schema.json` contains only locally supported observation fields. It includes no user or device identifier and no server receipt time.
- The `receivedAt` field in the backend-ingestion schema is **set by the backend**, not the client.
- All fields involving track metadata are nullable — not all media sources expose all fields.
