package io.github.pinguplay.linux

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
    private val BASE = io.github.pinguplay.BuildConfig.COMPONENTS_BASE_URL

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
        // Mesa main (27.0-devel): Adreno 830 entries, UBWC 5, Pinguplay direct present
        // (triple-buffered full-screen frames handed to the app without the X server), softpipe so
        // Xvfb can offer GLX while games render through Zink on the GPU, and async software
        // present (the GPU wait moved off the game's thread).
        version = "main-f01e9b0-pp7",
        sizeBytes = 7_202_496,
        sha256 = "99035daef43afbf35be55d1d4dca7214d5b89ffcf534106060977c9eeb4eb651",
        url = "${BASE}mesa-main-f01e9b0-pp7-pinguplay.tar.xz",
        replaces = "opt/pinguplay/mesa",
    )

    val BOX64 = Component(
        id = "box64",
        // Box64 main after v0.4.4 (LRCPC/LRCPC2 on Arm64) plus Pinguplay's patch that
        // skips FPCR rounding switches on exact int32 -> double conversions.
        version = "main-11a20e37-pp1",
        sizeBytes = 8_537_852,
        sha256 = "2ca875e28effb8db31820247710010ece184e7ff5ebb45973d63c82f4c67ef14",
        url = "${BASE}box64-main-11a20e37-pp1-pinguplay.tar.xz",
    )

    val TOOLS = Component(
        id = "tools",
        version = "17",
        sizeBytes = 27_648,
        sha256 = "6572fbf864549df784a2e059f5b766a2fc16804a79602cc641f26aae44c38d60",
        url = "${BASE}pinguplay-tools-17.tar.xz",
    )

    /** Order matters: the rootfs must exist before anything unpacks into it. */
    val ALL = listOf(ROOTFS, MESA, BOX64, TOOLS)
}
