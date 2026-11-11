# Changelog

## 0.2.1

- The RNode keeps its own screen unless **Custom display on radio** is turned on in the RNode settings. The switch is off by default. Turning it on shows the Reticulum logo on the radio. With it off, the next connection restores the radio's normal display.
- Plugging a supported LoRa radio in over USB opens the app, or brings it to the front if it is already running. The first time, Android asks which app should open for that device.
- Fixed short-term and long-term airtime limits being saved but not sent to the radio when Reticulum starts. They are applied when the radio connects.

## 0.2.0

- LXMF messenger: identity management and backup, announces, nodes list, direct and propagated messaging, propagation node sync and hosting.
- Interface Access Codes for TCP, UDP, Auto, I2P and RNode interfaces.
- RNode interface subclasses the RNS `Interface` base class, uses the real LoRa bitrate, and can be edited in place. Custom settings are the default, and radio settings can be exported and imported.
- LoRa diagnostics on the interface stats screen.
- Rebuilt network monitor for announces, paths and the rebroadcast queue.
