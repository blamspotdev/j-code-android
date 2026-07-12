# libandroid-shmem (JCode build)

Source for the bundled asset `../src/main/assets/bin/libandroid-shmem-arm64-v8a.so` —
the SysV shared-memory shim that proot's `--sysvipc` extension uses to emulate
`shmget`/`shmat`/`shmdt`/`shmctl` for guest programs (`libproot.so` DT_NEEDs it).

Upstream: https://github.com/termux/libandroid-shmem @ `7f0bd7e25dbdd146265aff7c6a890029e374622d`
(BSD license, see LICENSE).

JCode modifications (both marked `JCode modification` in shmem.c):

1. **memfd backend.** Regions are created with `memfd_create` + `ftruncate` and sized with
   `fstat`, replacing ashmem/`ASharedMemory`. Opening `/dev/ashmem` is SELinux-denied for apps
   targeting SDK >= 29 and the device node is gone from modern kernels; memfd needs no device,
   no ioctls, and no libandroid/libcutils dependency.
2. **Named-key registry path.** `ASHV_KEY_SYMLINK_PATH` points at
   `/data/data/dev.jcode/files/tmp/` (the Termux build hardcoded a com.termux path that does
   not exist here, making named-key `shmget` spin forever).

Rebuild (NDK r27c, from this directory):

```
$NDK/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android33-clang \
  -fpic -shared -std=c11 -Wall -Wextra -O2 \
  -Wl,--version-script=exports.txt -Wl,-soname,libandroid-shmem.so \
  -Wl,-z,max-page-size=16384 \
  shmem.c -o ../src/main/assets/bin/libandroid-shmem-arm64-v8a.so -llog
```

After changing the binary, bump `SUPPORT_LIBS_VERSION` in
`core/distro/src/main/java/dev/jcode/core/distro/ProotManager.kt` so existing installs
re-extract it.
