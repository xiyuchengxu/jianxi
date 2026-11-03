# Material Color Utilities subset

Upstream: https://github.com/material-foundation/material-color-utilities

Pinned commit: `bd811e4ca2ab94c4ce3cadc89c19b1820b9704b3`

License: Apache-2.0. The full license is bundled in
`app/src/main/res/raw/material_color_utilities_license.txt` and retained in the APK.
Original copyright headers remain in each source file.

Included files are the seven Java HCT, tonal-palette, and math/color utility sources.
The only source changes are relocating `hct`, `utils`, and `palettes` packages and
their imports under `dev.rain.laundry.vendor.material` to avoid package collisions.
The original color algorithms are unchanged.
