package tv.cleanyour.android.platform

import tv.cleanyour.core.model.AdbException
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.ConnectionState
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.model.RawShell
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.session.InstallOutcome
import tv.cleanyour.core.session.PushResult
import tv.cleanyour.core.session.TvSession

/**
 * Executes the reviewed phone-to-TV APK operation against one already authenticated TV session.
 * It never opens an arbitrary endpoint: the caller must supply the selected TV id and its session.
 */
class ApkTransferExecutor {
    @OptIn(RawShell::class)
    suspend fun transfer(
        expectedTv: TvId,
        session: TvSession,
        apk: ApkTransferStager.StagedApk,
        destination: RemotePath,
        onProgress: (Long) -> Unit = {},
    ): PushResult {
        require(session.tvId == expectedTv) { "The connected TV does not match the selected TV." }
        require(session.state.value is ConnectionState.Connected) { "Reconnect to the selected TV before transferring." }
        require(apk.blob.size == apk.info.size && apk.blob.sha256 == apk.info.sha256) {
            "The staged APK no longer matches the reviewed file."
        }
        require(apk.blob.size in 1..ApkTransferStager.MAX_APK_BYTES) { "APK size is outside the transfer limit." }
        val hold = session.hold()
        try {
            if (session.state.value !is ConnectionState.Connected) {
                throw IllegalStateException("The TV connection was lost before the transfer started.")
            }
            val directory = session.exec(ShellLine("mkdir -p /data/local/tmp/cleanyourtv"))
            check(directory.exit == 0) { "Could not prepare the TV's APK receive folder: ${directory.stderr.take(160)}" }
            val result = session.push(apk.blob, destination, onProgress)
            if (result.bytes != apk.info.size || result.remoteSha256 != apk.info.sha256) {
                throw IllegalStateException("The TV did not confirm the exact APK bytes. Installation is unavailable.")
            }
            return result
        } finally {
            hold.close()
        }
    }

    /**
     * Must only be called from a separate, explicit Install confirmation in the UI.
     * This streams the same reviewed local blob through the app's existing guarded install path.
     */
    suspend fun installAfterConfirmation(
        expectedTv: TvId,
        session: TvSession,
        apk: ApkTransferStager.StagedApk,
        confirmed: Boolean,
        onProgress: (Long) -> Unit = {},
    ): InstallOutcome {
        require(confirmed) { "Installation requires a separate confirmation." }
        require(session.tvId == expectedTv) { "The connected TV does not match the selected TV." }
        require(session.state.value is ConnectionState.Connected) { "Reconnect to the selected TV before installing." }
        require(apk.blob.size == apk.info.size && apk.blob.sha256 == apk.info.sha256) {
            "The staged APK no longer matches the reviewed file."
        }
        val hold = session.hold()
        try {
            return session.installStream(apk.blob, apk.info.size, onProgress)
        } finally {
            hold.close()
        }
    }
}
