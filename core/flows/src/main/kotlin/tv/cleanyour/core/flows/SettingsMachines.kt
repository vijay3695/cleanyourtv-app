package tv.cleanyour.core.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.cleanyour.core.engine.Engine
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.PlanRequest

// M7 TV settings, including an explicit, per-TV Private DNS workflow.
data class TvSettingsState(
    val name: String = "",
    val room: String? = null,
    val level: Level = Level.SIMPLE,
    val address: String? = null,
    val lastSeenAtMs: Long? = null,
    val confirmingForget: Boolean = false,
    val forgetting: Boolean = false,
    val dnsSupported: Boolean = false,
    val dnsLoading: Boolean = false,
    val dnsMode: String? = null,
    val dnsHost: String = "",
    val dnsChoice: String = "hostname",
    val dnsChoiceHost: String = "dns.adguard-dns.com",
    val dnsStatus: String = "Not checked",
    val confirmingDns: Boolean = false,
    val dnsRestorePending: Boolean = false,
    val dnsBusy: Boolean = false,
    val canRestoreDns: Boolean = false,
)
sealed interface TvSettingsIntent {
    data class Rename(val name: String, val room: String?) : TvSettingsIntent
    data class SetLevel(val level: Level) : TvSettingsIntent
    data object RefreshDns : TvSettingsIntent
    data class ChooseDnsMode(val mode: String) : TvSettingsIntent
    data class DnsHostname(val hostname: String) : TvSettingsIntent
    data object AskApplyDns : TvSettingsIntent
    data object CancelApplyDns : TvSettingsIntent
    data object ApplyDns : TvSettingsIntent
    data object AskRestoreDns : TvSettingsIntent
    data object RestoreDns : TvSettingsIntent
    data object AskForget : TvSettingsIntent
    data object CancelForget : TvSettingsIntent
    data object Forget : TvSettingsIntent
}
class TvSettingsMachine(
    private val d: ManageDeps,
    scope: CoroutineScope,
    private val tvId: TvId,
) : Machine<TvSettingsState, TvSettingsIntent, ManageEffect>(TvSettingsState(), scope) {
    private data class Loaded(val s: TvSettingsState)
    private data class Forgetting(val on: Boolean)
    private data class DnsChanged(val s: TvSettingsState)
    private val dnsBackupPath = "tvs/${tvId.value}/private-dns.txt"

    init {
        scope.launch {
            d.tvs.tvs.collect { list -> list.firstOrNull { it.id == tvId }?.let { tv ->
                send(Loaded(TvSettingsState(tv.nickname, tv.room, tv.level, tv.endpoints.firstOrNull()?.host, tv.lastSeenAt)))
            } }
        }
        scope.launch { refreshDns() }
    }
    override fun reduce(state: TvSettingsState, event: Any): TvSettingsState = when (event) {
        is Loaded -> event.s.copy(confirmingForget = state.confirmingForget, forgetting = state.forgetting,
            dnsSupported = state.dnsSupported, dnsLoading = state.dnsLoading, dnsMode = state.dnsMode,
            dnsHost = state.dnsHost, dnsChoice = state.dnsChoice, dnsChoiceHost = state.dnsChoiceHost,
            dnsStatus = state.dnsStatus, confirmingDns = state.confirmingDns, dnsRestorePending = state.dnsRestorePending, dnsBusy = state.dnsBusy,
            canRestoreDns = state.canRestoreDns)
        TvSettingsIntent.AskForget -> state.copy(confirmingForget = true)
        TvSettingsIntent.CancelForget -> state.copy(confirmingForget = false)
        is Forgetting -> state.copy(forgetting = event.on, confirmingForget = false)
        TvSettingsIntent.RefreshDns -> state.copy(dnsLoading = true, dnsStatus = "Reading settings from this TV…")
        is TvSettingsIntent.ChooseDnsMode -> state.copy(dnsChoice = event.mode)
        is TvSettingsIntent.DnsHostname -> state.copy(dnsChoiceHost = event.hostname.take(253))
        TvSettingsIntent.AskApplyDns -> state.copy(confirmingDns = true)
        TvSettingsIntent.CancelApplyDns -> state.copy(confirmingDns = false, dnsRestorePending = false)
        TvSettingsIntent.AskRestoreDns -> state.copy(confirmingDns = true, dnsRestorePending = true)
        is DnsChanged -> event.s.copy(confirmingForget = state.confirmingForget, forgetting = state.forgetting, dnsRestorePending = false)
        else -> state
    }
    override fun handle(event: Any, before: TvSettingsState, after: TvSettingsState) {
        when (event) {
            is TvSettingsIntent.Rename -> scope.launch {
                val name = event.name.trim().take(40)
                if (name.isNotEmpty()) d.tv(tvId)?.let { d.tvs.upsert(it.copy(nickname = name, room = event.room?.trim()?.take(40)?.ifEmpty { null })) }
            }
            is TvSettingsIntent.SetLevel -> scope.launch { d.tv(tvId)?.let { d.tvs.upsert(it.copy(level = event.level)) } }
            TvSettingsIntent.RefreshDns -> scope.launch { refreshDns() }
            TvSettingsIntent.ApplyDns -> if (!before.dnsBusy) scope.launch { if (before.dnsRestorePending) restoreDns() else applyDns(before.dnsChoice, before.dnsChoiceHost) }
            TvSettingsIntent.Forget -> if (!before.forgetting) scope.launch { forget() }
            else -> {}
        }
    }

    private suspend fun refreshDns() {
        send(DnsChanged(state.value.copy(dnsLoading = true, dnsStatus = "Reading settings from this TV…")))
        try {
            val session = (d.access.session(tvId) as? AccessResult.Ok)?.session
                ?: throw IllegalStateException("Could not connect to the selected TV")
            val snap = d.runner.readSnapshot(session)
            val mode = snap.settings[SettingKey.PRIVATE_DNS_MODE]
            val host = snap.settings[SettingKey.PRIVATE_DNS_SPECIFIER]
            if (snap.facts.sdk < 28) throw IllegalStateException("Private DNS requires Android 9 (API 28) or newer")
            if (mode !in setOf("off", "opportunistic", "hostname")) throw IllegalStateException("The TV did not return a supported Private DNS mode")
            if (mode == "hostname" && (host.isNullOrBlank() || !SettingKey.PRIVATE_DNS_SPECIFIER.valueShape.matches(host)))
                throw IllegalStateException("The TV's current DNS hostname could not be read safely")
            val backup = d.documents.read(dnsBackupPath) { it.decodeToString() }
            send(DnsChanged(state.value.copy(dnsLoading = false, dnsSupported = true, dnsMode = mode, dnsHost = host.orEmpty(),
                dnsStatus = "Current TV settings verified", canRestoreDns = backup != null)))
        } catch (e: Exception) {
            send(DnsChanged(state.value.copy(dnsLoading = false, dnsSupported = false, dnsMode = null,
                dnsStatus = e.message ?: "Could not read Private DNS settings")))
        }
    }

    private fun validHostname(value: String): String? {
        val host = value.trim().trimEnd('.').lowercase()
        return host.takeIf { SettingKey.PRIVATE_DNS_SPECIFIER.valueShape.matches(it) && !it.matches(Regex("[0-9.]+")) && ':' !in it }
    }
    private suspend fun applyDns(mode: String, inputHost: String, preserveBackup: Boolean = true) {
        send(DnsChanged(state.value.copy(dnsBusy = true, confirmingDns = false, dnsStatus = "Checking the TV before applying…")))
        try {
            if (mode !in setOf("off", "opportunistic", "hostname")) error("Choose a supported Private DNS mode")
            val host = if (mode == "hostname") validHostname(inputHost) ?: error("Enter a valid DNS hostname")
                else if (!preserveBackup) inputHost.takeIf { it.isNotEmpty() } else null
            val session = (d.access.session(tvId) as? AccessResult.Ok)?.session ?: error("Could not connect to the selected TV")
            val before = d.runner.readSnapshot(session)
            if (before.facts.sdk < 28) error("Private DNS requires Android 9 (API 28) or newer")
            val oldMode = before.settings[SettingKey.PRIVATE_DNS_MODE]
            val oldHost = before.settings[SettingKey.PRIVATE_DNS_SPECIFIER]
            if (oldMode !in setOf("off", "opportunistic", "hostname")) error("The current DNS mode is unknown; nothing was changed")
            if (oldMode == "hostname" && (oldHost.isNullOrBlank() || !SettingKey.PRIVATE_DNS_SPECIFIER.valueShape.matches(oldHost)))
                error("The current DNS hostname is unknown; nothing was changed")
            val result = TvWriter(d).write(tvId, PlanRequest.SetPrivateDns(mode, host), fresh = true)
            if (result !is WriteResult.Done) error("The TV did not confirm the DNS change: $result")
            if (preserveBackup) {
                val planMode = result.plan.steps.firstOrNull { (it.op as? Op.SettingsPut)?.key == SettingKey.PRIVATE_DNS_MODE }?.prior?.settingValue
                val planHost = result.plan.steps.firstOrNull { (it.op as? Op.SettingsPut)?.key == SettingKey.PRIVATE_DNS_SPECIFIER }?.prior?.settingValue
                val savedMode = planMode ?: oldMode
                val savedHost = planHost ?: oldHost
                d.documents.write(dnsBackupPath, (savedMode + "\n" + (savedHost?.let { "V" + it } ?: "N")).encodeToByteArray())
            }
            val verifySession = (d.access.session(tvId) as? AccessResult.Ok)?.session ?: error("DNS was changed, but the TV disconnected before verification. Reconnect and check its settings.")
            val after = d.runner.readSnapshot(verifySession)
            val expectedHost = host
            if (after.settings[SettingKey.PRIVATE_DNS_MODE] != mode ||
                after.settings[SettingKey.PRIVATE_DNS_SPECIFIER] != expectedHost)
                error("The TV's read-back did not match the requested DNS settings. Check the TV before trying again.")
            if (!preserveBackup) d.documents.delete(dnsBackupPath)
            send(DnsChanged(state.value.copy(dnsBusy = false, dnsSupported = true, dnsMode = mode,
                dnsHost = expectedHost.orEmpty(), dnsStatus = "Private DNS change verified on the TV", canRestoreDns = preserveBackup, dnsRestorePending = false)))
        } catch (e: Exception) {
            val recoverable = d.documents.read(dnsBackupPath) { true } ?: false
            send(DnsChanged(state.value.copy(dnsBusy = false, canRestoreDns = state.value.canRestoreDns || recoverable,
                dnsStatus = e.message ?: "Private DNS change failed")))
        }
    }
    private suspend fun restoreDns() {
        val saved = d.documents.read(dnsBackupPath) { it.decodeToString() } ?: run {
            send(DnsChanged(state.value.copy(dnsStatus = "No saved previous DNS settings"))); return
        }
        val lines = saved.split('\n')
        val mode = lines.getOrNull(0)
        val encodedHost = lines.getOrNull(1) ?: "N"
        val host = when {
            encodedHost == "N" -> null
            encodedHost.startsWith("V") -> encodedHost.drop(1)
            else -> null
        }
        if (mode !in setOf("off", "opportunistic", "hostname") || (mode == "hostname" && validHostname(host.orEmpty()) == null)) {
            send(DnsChanged(state.value.copy(dnsStatus = "Saved DNS settings are incomplete; refusing to restore"))); return
        }
        if (mode == "hostname" && host == null) {
            send(DnsChanged(state.value.copy(dnsStatus = "Saved DNS hostname is missing; refusing to restore"))); return
        }
        applyDns(mode!!, host.orEmpty(), preserveBackup = false)
    }

    private suspend fun forget() {
        val tv = d.tv(tvId) ?: return
        if (d.engine.isWorking(tvId)) { send(Forgetting(false)); effect(ManageEffect.Snackbar(SnackKind.RUN_BUSY, tv.nickname, undo = false)); return }
        d.gate.pass(tvId, tv.nickname) ?: return send(Forgetting(false))
        if (d.engine.isWorking(tvId)) { send(Forgetting(false)); effect(ManageEffect.Snackbar(SnackKind.RUN_BUSY, tv.nickname, undo = false)); return }
        send(Forgetting(true))
        attempt { d.sessionEnd.end(tvId) }
        attempt { d.sessions.existing(tvId)?.close() }
        d.documents.deleteTree("tvs/${tvId.value}")
        d.ledger.forget(tvId); d.gate.forget(tvId); d.tvs.forget(tvId)
        attempt { d.engine.abandon(tvId) }
        send(Forgetting(false)); effect(ManageEffect.Navigate("home"))
    }
}

// M8 App settings.
sealed interface UpdateCheck {
    data object Idle : UpdateCheck
    data object Checking : UpdateCheck
    data class Available(val version: String) : UpdateCheck
    data object Latest : UpdateCheck
    data object Failed : UpdateCheck
}

data class AppSettingsState(
    val version: String = "",
    val prefs: AppPrefsValue = AppPrefsValue(),
    val launcherPkg: String = "",
    val launcherVersion: String = "",
    val launcherDigest: String = "",
    val keyBacking: String = "",
    val keyFingerprint: String = "",
    val update: UpdateCheck = UpdateCheck.Idle,
    // STORES-PLAN 1: "Check for updates" exists only in the GitHub and F-Droid build, and the
    // privacy line names GitHub only when the app can reach it (an update check or an add-on).
    val updateCheck: Boolean = true,
    val addOns: Boolean = true,
    val confirm: AppConfirm? = null,
    val working: Boolean = false,
)

enum class AppConfirm { RESET_KEY, DELETE_EVERYTHING }

sealed interface AppSettingsIntent {
    data class SetHaptics(val on: Boolean) : AppSettingsIntent
    data class SetLargeText(val on: Boolean) : AppSettingsIntent
    data object CheckUpdates : AppSettingsIntent
    data object ReportProblem : AppSettingsIntent     // 0.3.2 B2: the report preview, its own screen
    data class Ask(val what: AppConfirm) : AppSettingsIntent
    data object CancelConfirm : AppSettingsIntent
    data object ResetKey : AppSettingsIntent
    data object DeleteEverything : AppSettingsIntent
}

// A run at work on the TV: running, waiting for the person's answer on the check screen, putting
// the TV's own home screen back (step R) or re-reading the step it was in doubt about. Forget this TV
// and Delete everything wait for it: they would delete the journal it is writing.
internal fun Engine.isWorking(tvId: TvId): Boolean = when (runState(tvId).value) {
    is RunState.Running, is RunState.AwaitingConfirm, is RunState.RollingBack, is RunState.InDoubt -> true
    else -> false
}

// The app-level actions are gated against a placeholder TV id: the gate is per TV (5.13) and
// "Reset my key" and "Delete everything" belong to no single TV.
val APP_GATE_ID = TvId("0000000000000000")

class AppSettingsMachine(
    private val d: ManageDeps,
    scope: CoroutineScope,
) : Machine<AppSettingsState, AppSettingsIntent, ManageEffect>(AppSettingsState(updateCheck = d.store.updateCheck, addOns = d.store.addOns), scope) {

    private data class Loaded(val s: AppSettingsState)
    private data class Update(val u: UpdateCheck)
    private data class Working(val on: Boolean)

    init {
        scope.launch {
            val pin = attempt { d.launcherPin.bundled() }.getOrNull()
            send(Loaded(AppSettingsState(
                version = d.appVersion, prefs = d.appPrefs.load(),
                launcherPkg = pin?.pkg?.value.orEmpty(), launcherVersion = pin?.versionName.orEmpty(),
                launcherDigest = pin?.let { keyDigest12(it.sha256) }.orEmpty(),
                keyBacking = attempt { d.keyInfo.backing }.getOrDefault(""), keyFingerprint = attempt { d.keyInfo.fingerprint }.getOrDefault(""),
                updateCheck = d.store.updateCheck, addOns = d.store.addOns,
            )))
        }
    }

    override fun reduce(state: AppSettingsState, event: Any): AppSettingsState = when (event) {
        is Loaded -> event.s.copy(update = state.update, confirm = state.confirm)
        is AppSettingsIntent.SetHaptics -> state.copy(prefs = state.prefs.copy(haptics = event.on))
        is AppSettingsIntent.SetLargeText -> state.copy(prefs = state.prefs.copy(largeTextFollowsSystem = event.on))
        AppSettingsIntent.CheckUpdates -> if (state.updateCheck) state.copy(update = UpdateCheck.Checking) else state
        is Update -> state.copy(update = event.u)
        is AppSettingsIntent.Ask -> state.copy(confirm = event.what)
        AppSettingsIntent.CancelConfirm -> state.copy(confirm = null)
        is Working -> state.copy(working = event.on, confirm = null)
        else -> state
    }

    override fun handle(event: Any, before: AppSettingsState, after: AppSettingsState) {
        when (event) {
            is AppSettingsIntent.SetHaptics, is AppSettingsIntent.SetLargeText -> scope.launch { d.appPrefs.save(after.prefs) }
            AppSettingsIntent.CheckUpdates -> if (d.store.updateCheck && before.update != UpdateCheck.Checking) scope.launch {
                val v = attempt { d.updates.latestNewerThan(d.appVersion) }.getOrNull()
                send(Update(when { v == null -> UpdateCheck.Failed; v.isEmpty() -> UpdateCheck.Latest; else -> UpdateCheck.Available(v) }))
            }
            AppSettingsIntent.ReportProblem -> effect(ManageEffect.Navigate("report/problem"))
            AppSettingsIntent.ResetKey -> if (!before.working) scope.launch { resetKey() }
            AppSettingsIntent.DeleteEverything -> if (!before.working) scope.launch { deleteEverything() }
            else -> {}
        }
    }

    // Delete everything waits for a run at work on any TV, as Forget this TV does.
    private fun refusedWhileWorking(): Boolean {
        val busy = d.tvs.tvs.value.firstOrNull { d.engine.isWorking(it.id) } ?: return false
        send(Working(false))
        effect(ManageEffect.Snackbar(SnackKind.RUN_BUSY_ANY, busy.nickname, undo = false))
        return true
    }

    // "Reset my key" (F2): every TV asks to trust this phone again.
    private suspend fun resetKey() {
        d.gate.passWithSubtitle(APP_GATE_ID, d.copy.text(CopyKey.GATE_SUBTITLE_RESET_KEY), forcePrompt = true) ?: return send(Working(false))
        send(Working(true))
        attempt { d.sessionEnd.endAll() }   // D7: every session closes here
        attempt { d.sessions.closeAll() }
        attempt { d.keyInfo.reset() }
        d.gate.clear()
        send(Working(false))
        send(Loaded(state.value.copy(keyFingerprint = attempt { d.keyInfo.fingerprint }.getOrDefault(""), keyBacking = attempt { d.keyInfo.backing }.getOrDefault(""))))
    }

    // "Delete everything" (F13): every TV record, journal, restore point and cached download, the key,
    // and the app's diagnostics log.
    private suspend fun deleteEverything() {
        if (refusedWhileWorking()) return
        d.gate.passWithSubtitle(APP_GATE_ID, d.copy.text(CopyKey.GATE_SUBTITLE_DELETE_ALL), forcePrompt = true) ?: return send(Working(false))
        if (refusedWhileWorking()) return
        send(Working(true))
        attempt { d.sessionEnd.endAll() }   // D7: every session closes here
        attempt { d.sessions.closeAll() }
        for (tv in d.tvs.tvs.value) {
            d.documents.deleteTree("tvs/${tv.id.value}")
            d.tvs.forget(tv.id)
            attempt { d.engine.abandon(tv.id) }   // as in Forget this TV: the engine drops a run whose journal is gone
        }
        attempt { d.documents.deleteTree("registry") }
        attempt { d.documents.delete("settings.txt") }
        attempt { d.documents.delete(CandidateChoices.PATH) }   // test build: the answers on the candidate list card
        attempt { d.keyInfo.reset() }
        attempt { d.diagnostics.clear() }   // the app's own diagnostics log goes too
        d.gate.clear()
        send(Working(false))
        effect(ManageEffect.Navigate("welcome"))
    }
}
