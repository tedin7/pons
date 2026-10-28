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
        // Box64 main after v0.4.4: LRCPC/LRCPC2 use on Arm64, many dynarec fixes.
        version = "main-11a20e37",
        sizeBytes = 8_528_000,
        sha256 = "ac346d7e98fd34c60b11c9124e9e9ec3979c6c096d5e0a1adf31d48b085e4b38",
        url = "${BASE}box64-main-11a20e37-pinguplay.tar.xz",
    )

    val TOOLS = Component(
        id = "tools",
        version = "5",
        sizeBytes = 19_424,
        sha256 = "b6496a201eef7035815c33a31c53891fbadb71fcb34d0aa9953226d5377285a7",
        url = "${BASE}pinguplay-tools-5.tar.xz",
    )

    /** Order matters: the rootfs must exist before anything unpacks into it. */
    val ALL = listOf(ROOTFS, MESA, BOX64, TOOLS)
}
