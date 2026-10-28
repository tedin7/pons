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
        // Mesa main (27.0-devel): Adreno 830 entries, UBWC 5, Pinguplay direct present.
        version = "main-f01e9b0-pp1",
        sizeBytes = 7_130_220,
        sha256 = "5b9ad966351dafbe1b761fef87a8f5740197a3bff1aa2273b4bee41ea2f4b8d2",
        url = "${BASE}mesa-main-f01e9b0-pp1-pinguplay.tar.xz",
        replaces = "opt/pinguplay/mesa",
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
        version = "3",
        sizeBytes = 3_684,
        sha256 = "7a58df198a69fa9ea6fa23d103a6d3ee2385071783c99898be27d9e2444bb7c8",
        url = "${BASE}pinguplay-tools-3.tar.xz",
    )

    /** Order matters: the rootfs must exist before anything unpacks into it. */
    val ALL = listOf(ROOTFS, MESA, BOX64, TOOLS)
}
