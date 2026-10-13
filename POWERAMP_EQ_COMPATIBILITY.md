# Poweramp Equalizer compatibility

This Echo Nightly fork adds an external Poweramp Equalizer launch and improves Android audio-session compatibility.

## What changed

- Echo announces its active Android audio session to audio-effect apps when playback starts, when the session changes, and when the playback service closes.
- The Audio Effects screen re-announces the current session and opens the installed Poweramp Equalizer app. If it is not installed, the button opens its Google Play listing.
- **External equalizer compatibility** is enabled by default. It disables audio offload, which can otherwise bypass Android audio effects on some devices. This may increase battery use.
- Echo's own Bass Boost can stack with Poweramp processing. Set Echo Bass Boost to zero when using Poweramp Equalizer to avoid double processing.
- Poweramp visualizations and visualization presets are used inside the Poweramp Equalizer app. Echo does not bundle Poweramp software, visualizer code, or preset files.

## Setup

1. Install Poweramp Equalizer from Google Play.
2. Start Echo playback, then open **Settings → Player → Audio Effects → Equalizer**. Echo will notify Poweramp about the current audio session and open Poweramp Equalizer.
3. In Poweramp Equalizer, enable Echo as a player if it is not detected automatically. Poweramp's own visualizations and presets are managed in Poweramp Equalizer.
4. If equalization does not take effect, leave **External equalizer compatibility** enabled and check the device's audio-output settings. Android version, device firmware, and output mode can affect external processing.

## Source and attribution

This project is based on the public `itsmechinmoy/echo-nightly` source at commit `f210820680cd5e7bdcb383e3daab3fa2c06b71e0`. The original `LICENSE.md` remains included. Poweramp and Poweramp Equalizer are separate products; this fork is not affiliated with or endorsed by their developer.

Poweramp's developer notes that external equalization depends on players exposing their Android audio-session IDs and may not work with offload or other non-standard output paths:

- https://forum.powerampapp.com/topic/23578-poweramp-equalizer-builds-937-938/
- https://forum.powerampapp.com/topic/20189-poweramp-equalizer-builds-886-893/
