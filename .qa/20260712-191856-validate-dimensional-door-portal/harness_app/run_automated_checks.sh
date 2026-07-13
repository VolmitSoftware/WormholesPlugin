#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"

./gradlew test \
  --tests art.arcane.wormholes.door.DoorPortalVisualServiceTest \
  --tests art.arcane.wormholes.door.DoorwayPlaneTest \
  --tests art.arcane.wormholes.door.DoorTransitGateTest \
  --tests art.arcane.wormholes.door.DoorIdentityTest \
  --tests art.arcane.wormholes.door.DoorItemPdcCodecTest \
  --tests art.arcane.wormholes.door.DimensionalDoorRepositoryTest \
  --tests art.arcane.wormholes.door.DoorSkinTest \
  --tests art.arcane.wormholes.door.DoorSkinRecipeTest \
  --tests art.arcane.wormholes.service.WormholesCommandServiceTest \
  --no-daemon
echo 'QA_EVT {"event":"dimensional_door_geometry_and_skins","status":"pass","details":"Focused geometry, transit, identity migration, command type, and skin tests passed"}'

./gradlew test \
  --tests art.arcane.wormholes.portal.ProjectionModeTest \
  --no-daemon
echo 'QA_EVT {"event":"black_gold_theme","status":"pass","details":"Black-and-gold projection theme tests passed"}'
