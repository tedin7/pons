# Third-party software

| Component | Where | License |
| --- | --- | --- |
| PRoot (Termux fork) | `libproot.so`, `libproot-loader.so`, patched by `tools/proot` | GPL-2.0 |
| talloc | `libtalloc.so` | LGPL-3.0 |
| libandroid-shmem (Termux) | `libandroid-shmem.so` | BSD-3-Clause |
| Debian trixie | downloaded root file system | various free licenses, see `/usr/share/common-licenses` |
| Mesa 3D (Turnip, Zink) | downloaded component | MIT |
| Box64 | downloaded component | MIT |
| Apache Commons Compress | app library | Apache-2.0 |
| XZ for Java | app library | 0BSD |
| Material Icons (`cloud_download`) | `app/src/main/res/drawable/ic_download.xml` | Apache-2.0 |

Source for each component is linked from its build script in `tools/`.
The Steam client is downloaded by the user's device from Valve and is covered by the
Steam Subscriber Agreement; Pons does not redistribute it.
