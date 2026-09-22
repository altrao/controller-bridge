package com.altrao.controllerbridge

/*
 * DELIBERATELY UNUSED -- safe to delete this file.
 *
 * The first draft of this project read `/dev/input/eventN` directly to get raw evdev
 * values. That approach was dropped, because it cannot actually work on a stock device:
 *
 *   - `/dev/input/event*` is owned by root:input with mode 0660. Reading it requires
 *     membership in the `input` group, which an installed app cannot obtain. It is not
 *     a runtime permission and it is not in the manifest.
 *   - The well-known `app_process` trick to add the group only works when the caller is
 *     already `shell` (uid 2000, which *is* in `input`) -- i.e. from `adb shell`, not
 *     from inside an application sandbox.
 *   - So the only paths were root, or a separate ADB shell daemon the user has to start
 *     by hand. Neither is "plug and play".
 *
 * InputDeviceReader replaces it and is strictly better for this use case:
 *   - needs no permission and no root
 *   - `MotionEvent.getAxisValue()` returns the full float range for triggers, which is
 *     the whole reason this project exists (the Unity client's binary trigger path)
 *   - it is the API Android actually supports, so it will not break on the next release
 *
 * Keeping this note rather than silently deleting the file so the reasoning is not
 * re-litigated later. Delete it whenever convenient.
 */
