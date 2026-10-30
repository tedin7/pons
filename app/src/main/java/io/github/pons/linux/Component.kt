package io.github.pons.linux

/**
 * A piece of the Linux environment shipped as a .tar.xz.
 * [assetName] is used when bundled in the APK, otherwise [url] is downloaded and
 * checked against [sha256]. [intoRootfs] unpacks over the Debian tree instead of
 * creating it. [replaces] is a rootfs path wiped before an upgrade, so files dropped
 * by a new version do not linger.
 */
data class Component(
    val id: String,
    val version: String,
    val sizeBytes: Long,
    val sha256: String,
    val url: String? = null,
    val assetName: String? = null,
    val intoRootfs: Boolean = true,
    val replaces: String? = null,
)

object Components {
    private val BASE = io.github.pons.BuildConfig.COMPONENTS_BASE_URL

    val ROOTFS = Component(
        id = "rootfs",
        version = "trixie-20261027",
        sizeBytes = 41_653_836,
        sha256 = "879affce4b258622af8eb4b09f1475408e21a251abed9bf3a29653bf3c656e4c",
        url = "${BASE}debian-slim.tar.xz",
        intoRootfs = false,
    )

    val MESA = Component(
        id = "mesa",
        // Mesa main (27.0-devel): Adreno 830 entries, UBWC 5, Pons direct present
        // (triple-buffered full-screen frames handed to the app without the X server), softpipe so
        // Xvfb can offer GLX while games render through Zink on the GPU, and async software
        // present (the GPU wait moved off the game's thread).
        version = "main-f01e9b0-pp10",
        sizeBytes = 7_205_888,
        sha256 = "abc4782241d750e72ae1d15b9be2e156ac724b5fce05ec59a7298035fed93259",
        url = "${BASE}mesa-main-f01e9b0-pp10-pons.tar.xz",
        replaces = "opt/pons/mesa",
    )

    val BOX64 = Component(
        id = "box64",
        // Box64 main after v0.4.4 (LRCPC/LRCPC2 on Arm64) plus Pons's patch that
        // skips FPCR rounding switches on exact int32 -> double conversions (and, since
        // pp12, whenever the rounding mode does not change), and one that saves the
        // translation cache while the game runs (games here are killed, not exited).
        // pp13: jump tables share their untouched pages (copy-on-write), ~0.6 GB less.
        version = "main-30d5c771-pp13",
        sizeBytes = 8_553_300,
        sha256 = "7329f1bedea9d0d1bc535ac43c6e8f8abc03d013914850f5b3cf50368817e54d",
        url = "${BASE}box64-main-30d5c771-pp13-pons.tar.xz",
    )

    val TOOLS = Component(
        id = "tools",
        version = "38",
        sizeBytes = 263_352,
        sha256 = "7ac0dbf362a9038fd35d700980805d5270677926983094304b34a4c5ab5ca03b",
        url = "${BASE}pons-tools-38.tar.xz",
    )

    /** Order matters: the rootfs must exist before anything unpacks into it. */
    val ALL = listOf(ROOTFS, MESA, BOX64, TOOLS)
}
