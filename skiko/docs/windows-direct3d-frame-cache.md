# Direct3D frame-aware resource reclamation

## Why the byte-only policy regressed frame rate

`0.152.0-voxzen.22` bounded **all purgeable resources** to the configured byte limit after every synchronous submission. Purgeable means safe to destroy, not unlikely to be reused. Ganesh's scratch cache also contains the full-window render targets used by blur, transparency and `saveLayer` on the next frame. A 16 MiB cap repeatedly destroyed those targets, especially at large window sizes.

Moving the same byte-only purge before submission or before flush did not fix the large-layer regression test. Neither boundary reliably distinguishes cold resources from deferred or unlocked hot scratch allocations.

## Policy in .23

- Record a native steady-clock marker at the beginning of a rendered frame, before surface setup and drawing
- Preserve the existing flush, synchronous submission, swap and live-resize behavior
- When purgeable bytes exceed `skiko.gpu.resourceCachePurgeableBytesLimit`, ask Ganesh to reclaim resources last made purgeable **before this frame**, using its existing ordered LRU queue
- Protect resources reused during this frame, including scratch targets; do not force their working set down to the reclamation trigger
- Keep the separate total resource-cache budget unchanged; the default negative trigger still disables this optional policy

This is submission-driven, not a periodic scanner. It uses Ganesh's public `performDeferredCleanup` API with the actual frame lifetime, rounded up to its millisecond precision. There is no fixed resource TTL, background timer, full scratch-queue sort, or per-texture tracking table added by Skiko. Cold resources below the trigger may remain cached; hot resources may exceed the trigger. The trigger is **not a total-memory guarantee**.

Ganesh maintains the purgeable-age marker when ownership returns to the cache, and its all-resource cleanup stops at the first sufficiently recent LRU entry. See the pinned [resource-cache implementation](https://github.com/JetBrains/skia/blob/m152-2ca5fe6a81/src/gpu/ganesh/GrResourceCache.cpp) and [public cleanup implementation](https://github.com/JetBrains/skia/blob/m152-2ca5fe6a81/src/gpu/ganesh/GrDirectContext.cpp).

## Compatibility

Only private renderer methods and the private JNI bridge change. `javap -protected` reports an identical `Direct3DRenderer` JVM ABI against .22. Public Skia/Skiko declarations and the pinned Skia m152 dependency are unchanged. Always upgrade the core jar and native runtime together; mixing private JNI implementations across releases is unsupported.

## Local regression validation, 2026-09-05

Voxzen `debugSteam` / Compose Hot Reload, JBR 25.0.3, 160 Hz display, Direct3D, Acrylic, synchronous live resize/move, 8 MiB native heap blocks, 192 MiB total cache budget and 16 MiB reclamation trigger. Flowing light, lyrics blur and cover reflection remain enabled.

| Scene | .22 render FPS | Frame-aware candidate FPS |
| --- | ---: | ---: |
| Maximized song list | 145.37 | 160.01 |
| Maximized playback | 101.14 | 159.95 |
| Player fullscreen playback, 2560 × 1440 | Not sampled | 160.00 |

These are steady-state render-delegate rates sampled in 12–23 second windows, corroborated by Skiko FPS logging, not a scanout/PresentMon measurement or a guarantee for all machines. A same-process .22 control disabling only the additional byte trim also restored approximately 160 FPS; Acrylic, smooth behavior and heap settings were unchanged.

The maximized list previously trimmed about 15,460 MiB/s; the candidate trims zero in steady state. Active playback trims approximately 15–17 MiB/s of changing content instead of about 13,761 MiB/s. These are cumulative resource eviction bytes, **not simultaneously resident memory**.

Voxzen's opt-in `Direct3DResourceCacheTest` covers hot full-window blur targets, retiring those targets, image churn, and default/16/8/4 MiB allocator blocks. The original hot-target test failed against .22 with 1,602 MiB evicted over 90 steady frames; the candidate evicted zero. Image churn still reclaimed approximately 216 MiB. The former small-window upload-only test did not exercise this regression.

This patch restores frame pacing without removing effects. It does not establish the separate cold-start 300 MiB / usage 500 MiB process-memory target, particularly for Debug with Hot Reload and a large playback surface.
