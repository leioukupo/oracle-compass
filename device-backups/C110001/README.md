# C110001 stock boot assets

This directory holds recovery copies read from the dedicated C110001 device before modifying its MTK `logo` partition.

Expected files under `stock/`:

- `logo-original.bin.gz`: complete original `logo` partition, gzip compressed.
- `bootanimation-original.zip`: stock `/system/media/bootanimation.zip` read from the Magisk mirror.
- `manifest.json`: sizes and SHA-256 values without serial numbers, addresses, credentials, or application settings.
- `SHA256SUMS`: hashes for the committed recovery files.

Use the authenticated Web console to restore the logo. It validates the original raw size and SHA-256, requires sufficient battery power, writes the complete partition, and verifies a complete readback. Do not write these files to a different hardware revision.

## Rebuilding slot 0

This hardware stores its power-on image in slot 0 as an `800x800` `rgbabe` buffer. Use `mtklogo` v0.1.2 at commit `e97f51944be9f6dbafff5b1d619341e1fa97dc4c`; the repository profile and builder replace only slot 0 and byte-compare all other 38 compressed slots after a round-trip unpack:

```bash
gzip -dc stock/logo-original.bin.gz > /tmp/logo-original.bin
tools/build-c110001-logo.sh /path/to/mtklogo \
  /tmp/logo-original.bin /path/to/oracle-compass-first-frame.jpg \
  /tmp/logo-custom.bin
```

The output is padded to the original complete partition size. Upload that complete image through the authenticated Web console; never flash a PNG or an individual compressed slot directly.
