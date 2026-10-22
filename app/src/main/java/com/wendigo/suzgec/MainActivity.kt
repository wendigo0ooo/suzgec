package com.wendigo.suzgec

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.wendigo.suzgec.core.DnsWire
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class MainActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var pages: List<View>
    private lateinit var nav: BottomNavigationView

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var btnToggle: Button
    private lateinit var statQueries: TextView
    private lateinit var statBlocked: TextView
    private lateinit var statToday: TextView
    private lateinit var ruleInfo: TextView
    private lateinit var portInfo: TextView
    private lateinit var recentList: LinearLayout
    private lateinit var testResult: TextView
    private var recentSignature: String = ""

    private lateinit var clientChips: ChipGroup
    private lateinit var tunnelSteps: TextView
    private lateinit var tunnelConfig: TextView
    private lateinit var tunnelTestResult: TextView

    private lateinit var etPort: EditText
    private lateinit var rgUpstream: RadioGroup
    private lateinit var etCustomDoh: EditText
    private lateinit var btnSaveCustom: Button
    private lateinit var rgBlockMode: RadioGroup
    private lateinit var swBundled: SwitchCompat
    private lateinit var etListUrl: EditText
    private lateinit var listContainer: LinearLayout
    private lateinit var settingsInfo: TextView
    private lateinit var aboutText: TextView

    private var currentPage = 0
    private var busy = false
    private var syncingNav = false

    private lateinit var recommendedChips: ChipGroup

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            FilterService.start(this)
        } else {
            toast(getString(R.string.notif_permission_needed))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        store = Store(this)
        Stats.init(this)
        setContentView(R.layout.activity_main)

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !night

        pages = listOf(
            findViewById(R.id.pageStatus),
            findViewById(R.id.pageTunnel),
            findViewById(R.id.pageSettings)
        )
        nav = findViewById(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            if (syncingNav) return@setOnItemSelectedListener true
            val index = when (item.itemId) {
                R.id.nav_status -> 0
                R.id.nav_tunnel -> 1
                else -> 2
            }
            selectPage(index, syncNav = false)
            true
        }

        bindStatusPage()
        bindTunnelPage()
        bindSettingsPage()

        currentPage = savedInstanceState?.getInt(KEY_PAGE, 0) ?: 0
        selectPage(currentPage)

        if (store.serviceWanted && !FilterService.running) {
            try {
                FilterService.start(this)
            } catch (_: Exception) {
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PAGE, currentPage)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun selectPage(index: Int, syncNav: Boolean = true) {
        currentPage = index
        pages.forEachIndexed { i, view ->
            view.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        if (!syncNav) return
        val itemId = when (index) {
            0 -> R.id.nav_status
            1 -> R.id.nav_tunnel
            else -> R.id.nav_settings
        }
        if (nav.selectedItemId != itemId) {
            syncingNav = true
            try {
                nav.selectedItemId = itemId
            } finally {
                syncingNav = false
            }
        }
    }

    private fun bindStatusPage() {
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        statusDetail = findViewById(R.id.statusDetail)
        btnToggle = findViewById(R.id.btnToggle)
        statQueries = findViewById(R.id.statQueries)
        statBlocked = findViewById(R.id.statBlocked)
        statToday = findViewById(R.id.statToday)
        ruleInfo = findViewById(R.id.ruleInfo)
        portInfo = findViewById(R.id.portInfo)
        recentList = findViewById(R.id.recentList)
        testResult = findViewById(R.id.testResult)

        btnToggle.setOnClickListener {
            if (FilterService.running) {
                FilterService.stop(this)
            } else {
                startFiltering()
            }
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            if (!FilterService.running) {
                testResult.text = getString(R.string.test_need_start)
                return@setOnClickListener
            }
            runTest(testResult)
        }
        findViewById<Button>(R.id.btnResetStats).setOnClickListener {
            Stats.resetToday()
            recentSignature = ""
            updateStatus()
        }
        updateStatus()
    }

    private fun startFiltering() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            FilterService.start(this)
        }
    }

    private fun updateStatus() {
        val running = FilterService.running
        statusDot.setBackgroundResource(if (running) R.drawable.dot_on else R.drawable.dot_off)
        statusText.text = if (running) {
            getString(R.string.status_running)
        } else {
            getString(R.string.status_stopped)
        }

        val error = FilterService.lastError
        statusDetail.text = when {
            FilterService.loading -> getString(R.string.status_detail_loading)
            running -> getString(
                R.string.status_detail_running,
                FilterService.boundPort,
                FilterService.ruleCount
            )
            !error.isNullOrBlank() -> error
            else -> getString(R.string.status_detail_stopped, store.port)
        }

        btnToggle.isEnabled = !FilterService.loading

        btnToggle.text = if (running) getString(R.string.action_stop) else getString(R.string.action_start)
        btnToggle.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (running) R.color.danger else R.color.accent)
        )

        statQueries.text = formatCount(Stats.total)
        statBlocked.text = formatCount(Stats.blocked)
        statToday.text = getString(
            R.string.stats_today,
            Stats.todayQueryCount(),
            Stats.todayBlockedCount()
        )
        ruleInfo.text = getString(
            R.string.stats_rules,
            FilterService.ruleCount,
            FilterService.sourceCount
        )
        portInfo.text = getString(R.string.stats_port, if (running) FilterService.boundPort else store.port)

        val recent = Stats.recentBlocked()
        val signature = buildString {
            append(recent.size)
            append('|')
            append(recent.firstOrNull()?.host ?: "")
        }
        if (signature != recentSignature) {
            recentSignature = signature
            rebuildRecent(recent)
        }
    }

    private fun rebuildRecent(recent: List<Stats.Recent>) {
        recentList.removeAllViews()
        if (recent.isEmpty()) {
            val empty = TextView(this)
            empty.text = getString(R.string.recent_empty)
            empty.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
            empty.textSize = 13f
            empty.setPadding(0, dp(6), 0, dp(6))
            recentList.addView(empty)
            return
        }
        val shown = recent.take(12)
        for (item in shown) {
            val row = TextView(this)
            row.text = item.host
            row.setTextColor(ContextCompat.getColor(this, R.color.text))
            row.textSize = 13.5f
            row.setPadding(0, dp(4), 0, dp(4))
            recentList.addView(row)
        }
    }

    private fun bindTunnelPage() {
        clientChips = findViewById(R.id.clientChips)
        tunnelSteps = findViewById(R.id.tunnelSteps)
        tunnelConfig = findViewById(R.id.tunnelConfig)
        tunnelTestResult = findViewById(R.id.tunnelTestResult)

        val clients = tunnelClients()
        clientChips.isSingleSelection = true
        clientChips.isSelectionRequired = true
        for (client in clients) {
            val chip = Chip(this)
            chip.text = client.label
            chip.isCheckable = true
            chip.tag = client.id
            chip.setEnsureMinTouchTargetSize(false)
            clientChips.addView(chip)
            if (client.id == store.lastClient) chip.isChecked = true
        }
        if (clientChips.checkedChipId == View.NO_ID && clientChips.childCount > 0) {
            (clientChips.getChildAt(0) as Chip).isChecked = true
        }

        clientChips.setOnCheckedStateChangeListener { group, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(id) ?: return@setOnCheckedStateChangeListener
            val clientId = chip.tag as? String ?: return@setOnCheckedStateChangeListener
            store.lastClient = clientId
            applyClient(clients.firstOrNull { it.id == clientId })
        }

        applyClient(clients.firstOrNull { it.id == store.lastClient } ?: clients.first())

        findViewById<Button>(R.id.btnCopyConfig).setOnClickListener {
            copyToClipboard(getString(R.string.copy_label_config), tunnelConfig.text.toString())
        }
        findViewById<Button>(R.id.btnCopyDns).setOnClickListener {
            copyToClipboard(getString(R.string.copy_label_dns), "127.0.0.1:${store.port}")
        }
        findViewById<Button>(R.id.btnTunnelTest).setOnClickListener {
            if (!FilterService.running) {
                tunnelTestResult.text = getString(R.string.test_need_start)
                return@setOnClickListener
            }
            runTest(tunnelTestResult)
        }
    }

    private data class TunnelClient(
        val id: String,
        val label: String,
        val steps: String,
        val config: String
    )

    private fun tunnelClients(): List<TunnelClient> {
        val dns = "127.0.0.1:${store.port}"
        return listOf(
            TunnelClient(
                "v2rayng",
                "v2rayNG",
                getString(R.string.tunnel_v2rayng_steps, dns),
                "{\n  \"dns\": {\n    \"servers\": [\"$dns\"]\n  }\n}"
            ),
            TunnelClient(
                "hiddify",
                "Hiddify",
                getString(R.string.tunnel_hiddify_steps, dns),
                "Remote DNS: $dns\nDirect DNS: $dns"
            ),
            TunnelClient(
                "nekobox",
                "NekoBox",
                getString(R.string.tunnel_nekobox_steps, dns),
                "DNS sunucusu: $dns"
            ),
            TunnelClient(
                "singbox",
                "sing-box",
                getString(R.string.tunnel_singbox_steps, dns),
                "{\n  \"dns\": {\n    \"servers\": [\n      {\n        \"tag\": \"suzgec\",\n        \"address\": \"udp://$dns\",\n        \"detour\": \"direct\"\n      }\n    ]\n  }\n}"
            ),
            TunnelClient(
                "wireguard",
                "WireGuard",
                getString(R.string.tunnel_wireguard_steps),
                "[Interface]\n# WireGuard DNS alanı port kabul etmez.\n# Bu yüzden 127.0.0.1:5353 doğrudan kullanılamaz.\n# Sunucu tarafında Pi-hole / AdGuard Home önerilir."
            )
        )
    }

    private fun applyClient(client: TunnelClient?) {
        if (client == null) return
        tunnelSteps.text = client.steps
        tunnelConfig.text = client.config
        tunnelTestResult.text = ""
    }

    private fun bindSettingsPage() {
        etPort = findViewById(R.id.etPort)
        rgUpstream = findViewById(R.id.rgUpstream)
        etCustomDoh = findViewById(R.id.etCustomDoh)
        btnSaveCustom = findViewById(R.id.btnSaveCustom)
        rgBlockMode = findViewById(R.id.rgBlockMode)
        swBundled = findViewById(R.id.swBundled)
        etListUrl = findViewById(R.id.etListUrl)
        listContainer = findViewById(R.id.listContainer)
        settingsInfo = findViewById(R.id.settingsInfo)
        aboutText = findViewById(R.id.aboutText)
        recommendedChips = findViewById(R.id.recommendedChips)

        etPort.setText(store.port.toString())
        etPort.inputType = InputType.TYPE_CLASS_NUMBER

        when (store.upstreamKey) {
            "adguard" -> rgUpstream.check(R.id.rbAdguard)
            "quad9" -> rgUpstream.check(R.id.rbQuad9)
            "google" -> rgUpstream.check(R.id.rbGoogle)
            "custom" -> rgUpstream.check(R.id.rbCustom)
            else -> rgUpstream.check(R.id.rbCloudflare)
        }
        etCustomDoh.setText(store.customDoh)
        etCustomDoh.visibility = if (store.upstreamKey == "custom") View.VISIBLE else View.GONE
        btnSaveCustom.visibility = etCustomDoh.visibility

        rgUpstream.setOnCheckedChangeListener { _, checkedId ->
            store.upstreamKey = when (checkedId) {
                R.id.rbAdguard -> "adguard"
                R.id.rbQuad9 -> "quad9"
                R.id.rbGoogle -> "google"
                R.id.rbCustom -> "custom"
                else -> "cloudflare_ip"
            }
            val custom = store.upstreamKey == "custom"
            etCustomDoh.visibility = if (custom) View.VISIBLE else View.GONE
            btnSaveCustom.visibility = etCustomDoh.visibility
            reloadIfRunning()
        }
        btnSaveCustom.setOnClickListener {
            val url = etCustomDoh.text.toString().trim()
            if (!url.startsWith("https://")) {
                toast(getString(R.string.err_https_required))
                return@setOnClickListener
            }
            store.customDoh = url
            reloadIfRunning()
            toast(getString(R.string.saved))
        }

        if (store.blockMode == Store.MODE_ZERO) rgBlockMode.check(R.id.rbZero) else rgBlockMode.check(R.id.rbNxdomain)
        rgBlockMode.setOnCheckedChangeListener { _, checkedId ->
            store.blockMode = if (checkedId == R.id.rbZero) Store.MODE_ZERO else Store.MODE_NXDOMAIN
            reloadIfRunning()
        }

        swBundled.isChecked = store.useBundled
        swBundled.setOnCheckedChangeListener { _, checked ->
            store.useBundled = checked
            reloadIfRunning()
        }

        findViewById<Button>(R.id.btnSavePort).setOnClickListener {
            val value = etPort.text.toString().trim().toIntOrNull()
            if (value == null || value < 1024 || value > 65535) {
                toast(getString(R.string.err_port))
                return@setOnClickListener
            }
            store.port = value
            etPort.clearFocus()
            reloadIfRunning()
            updateStatus()
            applyClient(tunnelClients().firstOrNull { it.id == store.lastClient })
            toast(getString(R.string.saved))
        }

        findViewById<Button>(R.id.btnAddList).setOnClickListener { addList() }
        findViewById<Button>(R.id.btnReload).setOnClickListener { refreshLists() }
        findViewById<Button>(R.id.btnDiagnostics).setOnClickListener { copyDiagnostics() }

        setupRecommendedChips()
        rebuildListRows()
    }

    private fun setupRecommendedChips() {
        recommendedChips.removeAllViews()
        for (item in Recommended.LISTS) {
            val chip = Chip(this)
            chip.text = item.label
            chip.isCheckable = false
            chip.isClickable = true
            chip.setEnsureMinTouchTargetSize(false)
            chip.setOnClickListener {
                if (store.userLists.contains(item.url)) {
                    toast(getString(R.string.err_list_exists))
                } else {
                    addListFrom(item.url)
                }
            }
            recommendedChips.addView(chip)
        }
    }

    private fun reloadIfRunning() {
        if (FilterService.running) FilterService.reload(this)
    }

    private fun rebuildListRows() {
        listContainer.removeAllViews()
        val urls = store.userLists.toList().sorted()
        val filterStore = FilterStore(this)
        if (urls.isEmpty()) {
            val empty = TextView(this)
            empty.text = getString(R.string.lists_empty)
            empty.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
            empty.textSize = 13f
            listContainer.addView(empty)
        } else {
            for (url in urls) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.VERTICAL
                row.setPadding(0, dp(8), 0, dp(8))

                val title = TextView(this)
                title.text = url
                title.setTextColor(ContextCompat.getColor(this, R.color.text))
                title.textSize = 13f
                row.addView(title)

                val sub = TextView(this)
                val lines = try {
                    filterStore.lineCount(url)
                } catch (_: Exception) {
                    0
                }
                sub.text = getString(R.string.list_line_count, lines)
                sub.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
                sub.textSize = 12f
                row.addView(sub)

                val remove = Button(this)
                remove.text = getString(R.string.action_remove)
                remove.textSize = 13f
                remove.setBackgroundColor(0)
                remove.setTextColor(ContextCompat.getColor(this, R.color.danger))
                remove.setPadding(0, dp(2), 0, dp(2))
                remove.minHeight = 0
                remove.minimumHeight = 0
                remove.setOnClickListener { confirmRemoveList(url) }
                row.addView(remove)

                listContainer.addView(row)
            }
        }
        settingsInfo.text = getString(
            R.string.settings_info,
            FilterService.ruleCount,
            FilterService.sourceCount
        )
        val version = packageVersion()
        aboutText.text = getString(R.string.about_text, version)
    }

    private fun confirmRemoveList(url: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_list_title)
            .setMessage(url)
            .setPositiveButton(R.string.action_remove) { _, _ ->
                FilterStore(this).remove(url)
                store.userLists = store.userLists - url
                rebuildListRows()
                reloadIfRunning()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun addList() {
        val url = etListUrl.text.toString().trim()
        if (!url.startsWith("https://")) {
            toast(getString(R.string.err_https_required))
            return
        }
        addListFrom(url)
    }

    private fun addListFrom(url: String) {
        if (busy) return
        if (store.userLists.contains(url)) {
            toast(getString(R.string.err_list_exists))
            return
        }
        busy = true
        toast(getString(R.string.list_downloading))
        Thread {
            val result = try {
                val text = FilterStore(this).download(url)
                FilterStore(this).save(url, text)
                store.userLists = store.userLists + url
                getString(R.string.list_added)
            } catch (e: Exception) {
                e.message ?: getString(R.string.err_generic)
            }
            runOnUiThread {
                busy = false
                toast(result)
                etListUrl.setText("")
                rebuildListRows()
                reloadIfRunning()
            }
        }.start()
    }

    private fun refreshLists() {
        if (busy) return
        val urls = store.userLists.toList()
        if (urls.isEmpty()) {
            if (FilterService.running) FilterService.reload(this)
            toast(getString(R.string.reloading))
            return
        }
        busy = true
        toast(getString(R.string.reloading))
        Thread {
            var ok = 0
            var failed = 0
            for (url in urls) {
                try {
                    val text = FilterStore(this).download(url)
                    FilterStore(this).save(url, text)
                    ok++
                } catch (_: Exception) {
                    failed++
                }
            }
            runOnUiThread {
                busy = false
                rebuildListRows()
                if (FilterService.running) FilterService.reload(this)
                toast(getString(R.string.refresh_done, ok, failed))
            }
        }.start()
    }

    private fun copyDiagnostics() {
        val text = buildString {
            appendLine("Süzgeç ${packageVersion()}")
            appendLine("durum: ${if (FilterService.running) "çalışıyor" else "durdu"}")
            appendLine("port: ${if (FilterService.running) FilterService.boundPort else store.port}")
            appendLine("kural: ${FilterService.ruleCount} · kaynak: ${FilterService.sourceCount}")
            appendLine("yükleniyor: ${FilterService.loading}")
            appendLine("son hata: ${FilterService.lastError ?: "-"}")
            appendLine("upstream: ${store.upstreamKey}${if (store.upstreamKey == "custom") " ${store.customDoh}" else ""}")
            appendLine("liste sayısı: ${store.userLists.size}")
            appendLine("bugün: ${Stats.todayQueryCount()} sorgu · ${Stats.todayBlockedCount()} engellendi")
            val crash = CrashLog.read(this@MainActivity)
            if (crash.isNotBlank()) {
                appendLine()
                appendLine("--- son kayıtlar ---")
                append(crash)
            }
        }
        copyToClipboard(getString(R.string.copy_label_diagnostics), text)
    }

    private fun packageVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (_: Exception) {
        "1.0"
    }

    private fun runTest(target: TextView) {
        target.text = getString(R.string.test_running)
        val port = if (FilterService.running) FilterService.boundPort else store.port
        Thread {
            val blocked = resolveViaLocal("doubleclick.net", port)
            val passed = resolveViaLocal("example.com", port)
            val message = buildString {
                append(getString(R.string.test_blocked_line, "doubleclick.net", blocked ?: getString(R.string.test_fail)))
                append('\n')
                append(getString(R.string.test_pass_line, "example.com", passed ?: getString(R.string.test_fail)))
            }
            runOnUiThread { target.text = message }
        }.start()
    }

    private fun resolveViaLocal(name: String, port: Int): String? {
        var socket: DatagramSocket? = null
        return try {
            val query = DnsWire.buildQuery(name)
            val s = DatagramSocket()
            socket = s
            s.soTimeout = 3500
            s.send(DatagramPacket(query, query.size, InetAddress.getByName("127.0.0.1"), port))
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            s.receive(packet)
            val response = buffer.copyOf(packet.length)
            when (val code = DnsWire.rcode(response)) {
                0 -> if (DnsWire.readU16(response, 6) > 0) "NOERROR" else "BOŞ"
                2 -> "SERVFAIL"
                3 -> "NXDOMAIN"
                else -> "RCODE $code"
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(getString(R.string.copied))
    }

    private fun formatCount(value: Long): String = when {
        value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 10_000 -> String.format(java.util.Locale.US, "%.1fK", value / 1000.0)
        else -> value.toString()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_PAGE = "page_index"
    }
}
