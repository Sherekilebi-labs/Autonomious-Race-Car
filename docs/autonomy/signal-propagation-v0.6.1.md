# Signal timing collector 0.6.1

Prepared after reviewing session `6cf0c96f820f82d7c6c9b975ff23181c`. Installable APK: `dist/signal-monitor/maxbot-0.6.1-timing-fix.apk`, Android version code 11.

## Corrections

- Camera records now include `android_camera_timestamp_ns` from ARCore's explicit Android camera API. Capture-clock eligibility requires the selected camera's REALTIME source, CPU image timestamp equality with that Android timestamp, and plausible nonnegative image age. ARCore's general frame timestamp is retained for diagnosis but is no longer used for the equality check. This follows [ARCore's timestamp distinction](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame). The new correlation must be confirmed on the Pixel; it will continue to report capture timing as unavailable if those checks fail.
- The Mac warning UI ignores duplicate and older image IDs before changing the displayed warning or its age. Explicit stale/inactive states still apply. The frame ordering resets when a new phone connection arrives.
- Analysis revision 2 counts the first Mac presentation once per frame and excludes the first five vision frames consistently. It also reports image-available → first Mac warning opportunity separately.
- Phone processing ends at the recorded post-supervisor marker. The transmit interval uses the consumed command's own producer timestamp and is explicitly labelled as including producer work.

YOLOv5n weights, input resolution, decoder, confidence/ROI thresholds, drive ramps and motor-control code are unchanged. This release corrects measurement and warning freshness; it is not an inference-performance optimization.

## Installation and next recording

Install the new APK over MaxBot, restart the Mac dashboard using the refreshed `dist/signal-monitor/start-mac.command`, and reload the browser page. The ESP firmware is unchanged from the version already flashed; another ESP flash is unnecessary for these corrections. No device is installed, flashed or started automatically by preparing this release.

First do a short stationary, disarmed 10-second monitoring check. Save the JSON even if the camera results remain unavailable; the added Android timestamp lets that condition be diagnosed. For the actual next experiments, use the [review's controlled protocols](reports/2026-11-16-signal-propagation-review.md#recommended-next-runs-in-priority-order).

The original received JSON/Markdown and their original summary remain unchanged. Reanalyzing them with revision 2 will intentionally change the Mac sample counts and phone stage definitions; use the review/audit for a documented comparison.
