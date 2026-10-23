## Unreleased — official 1.0.40 backport
* Added the upstream-compatible custom Java / KubeJS renderer with persistent settings and a built-in beacon.
* Added client Forge and KubeJS renderer registration, preserving Java callbacks when scripts register renderers.
* Fixed custom renderer pose restoration on failure, configurable view distance, and fractional beacon glow radius.
* Enabled Java parameter metadata for KubeJS.
* Verified the existing upstream slot predicate, fluid auto-input, entity preview, and biome tooltip fixes; added fluid and renderer regression coverage.
* Added Chinese / English editor labels and usage documentation in `docs/official-1.0.40-port.md`.

## v1.0.38.a
* hot fix: disable formed rendering

