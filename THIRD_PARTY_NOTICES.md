# Third-Party Notices

This file records third-party projects that may be installed, invoked, or distributed alongside Mini Java Terminal (MJT).

## PRoot-Distro

- **Project:** PRoot-Distro
- **Upstream repository:** https://github.com/termux/proot-distro
- **Maintained by:** the Termux / PRoot-Distro contributors
- **Purpose:** rootless Linux container management on Termux and regular Linux hosts
- **License:** GNU General Public License, version 3.0 (GPL-3.0)

MJT may use `proot-distro` as an upstream runtime component for managed PRoot guest environments. `proot-distro` is not relicensed by MJT.

## Distribution guidance

MJT's original Java source is released under the MIT License. The following applies when `proot-distro` is involved:

1. **Invocation only:** If MJT downloads or invokes an independently distributed `proot-distro` program without copying or modifying its source, retain this attribution and keep the upstream license information available to users.
2. **Bundling or redistribution:** If an MJT release bundles a `proot-distro` wheel, executable, source archive, or other copy of the upstream program, include the corresponding GPL-3.0 license text, copyright notices, and applicable source-availability information with that distribution.
3. **Modification or incorporation:** If code from `proot-distro` is copied into, modified within, or otherwise incorporated into MJT, that combined derivative work may be subject to GPL-3.0 requirements. Do not describe such a combined distribution as MIT-only.
4. **Upstream notices:** Preserve upstream notices and clearly identify local modifications when redistributing modified upstream code.

This notice is provided for project hygiene and attribution. It is not legal advice; obtain qualified licensing advice before shipping a combined or bundled distribution.
