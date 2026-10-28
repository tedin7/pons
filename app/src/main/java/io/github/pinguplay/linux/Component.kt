package io.github.pinguplay.linux

/**
 * A piece of the Linux environment shipped as a .tar.xz.
 * [assetName] is used when bundled in the APK, otherwise [url] is downloaded and
 * checked against [sha256]. [intoRootfs] unpacks over the Debian tree instead of
 * creating it.
 */
data class Component(
    val id: String,
    val version: String,
    val sizeBytes: Long,
    val sha256: String,
    val url: String? = null,
    val assetName: String? = null,
    val intoRootfs: Boolean = true,
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
        version = "26.2.5",
        sizeBytes = 7_055_488,
        sha256 = "dec0fc2155bc7ef38d28ca9d7397d7fc122a7e15f496754204f04af4443007cf",
        url = "${BASE}mesa-26.2.5-pinguplay.tar.xz",
    )

    val BOX64 = Component(
        id = "box64",
        version = "v0.4.4",
        sizeBytes = 8_512_536,
        sha256 = "2d7a20df694b493665e5e11362c3ffcb000f8234c76053ff0c377b9b5461772c",
        url = "${BASE}box64-v0.4.4-pinguplay.tar.xz",
    )

    val TOOLS = Component(
        id = "tools",
        version = "2",
        sizeBytes = 3_084,
        sha256 = "d226793a46e3d6b030e0280c8ec9128291832548af42b8e0440990a1debaa751",
        url = "${BASE}pinguplay-tools-2.tar.xz",
    )

    /** Order matters: the rootfs must exist before anything unpacks into it. */
    val ALL = listOf(ROOTFS, MESA, BOX64, TOOLS)
}
