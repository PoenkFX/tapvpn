package com.tapvpn.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.tapvpn.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var pendingToggle = false

    private val vpnPrepare = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            LocalVpnService.start(this)
            OverlayService.arm(this)
        } else {
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
        }
        pendingToggle = false
    }

    private val overlayPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val allowed = Settings.canDrawOverlays(this)
        if (!allowed) prefs.overlayOn = false
        bindOverlay()
        if (pendingStart && allowed) armVpn()
        pendingStart = false
    }

    private val notificationsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* channel still works at IMPORTANCE_LOW */ }

    private val vpnListener: (Boolean) -> Unit = { on ->
        runOnUiThread { if (!isDestroyed) renderVpn(on) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        if (Build.VERSION.SDK_INT >= 33) {
            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.orb.setOnClickListener { toggleVpn() }
        binding.sizeSeek.max = Prefs.MAX_SIZE - Prefs.MIN_SIZE
        binding.sizeSeek.progress = prefs.bubbleSizeDp - Prefs.MIN_SIZE
        binding.sizeValue.text = getString(R.string.size_dp, prefs.bubbleSizeDp)
        binding.sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = Prefs.MIN_SIZE + progress
                prefs.bubbleSizeDp = size
                binding.sizeValue.text = getString(R.string.size_dp, size)
                if (fromUser && prefs.overlayOn) OverlayService.resize(this@MainActivity)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        listOf(binding.preset48, binding.preset64, binding.preset80, binding.preset96).forEach { chip ->
            chip.setOnClickListener {
                val size = chip.tag.toString().toInt()
                prefs.bubbleSizeDp = size
                binding.sizeSeek.progress = size - Prefs.MIN_SIZE
                binding.sizeValue.text = getString(R.string.size_dp, size)
                if (prefs.overlayOn) OverlayService.resize(this)
            }
        }

        binding.timerSeek.max = 99
        binding.timerSeek.progress = prefs.timerTenths - 1
        binding.timerSwitch.isChecked = prefs.timerEnabled
        renderTimer()
        binding.timerSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.timerEnabled = checked
            renderTimer()
            if (!checked) OverlayService.disarm(this)
        }
        binding.timerSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.timerTenths = progress + 1
                renderTimer()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        renderVpn(VpnState.isOn())
        bindOverlay()
        showLastCrash()
    }

    private fun showLastCrash() {
        val text = CrashLog.read(this) ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_title)
            .setMessage(text)
            .setPositiveButton(R.string.crash_close) { _, _ -> CrashLog.clear(this) }
            .show()
    }

    override fun onStart() {
        super.onStart()
        VpnState.addListener(vpnListener)
        renderVpn(VpnState.isOn())
        if (prefs.overlayOn && Settings.canDrawOverlays(this)) OverlayService.start(this)
    }

    override fun onStop() {
        VpnState.removeListener(vpnListener)
        super.onStop()
    }

    private var pendingStart = false

    private fun toggleVpn() {
        if (VpnState.isOn()) {
            OverlayService.disarm(this)
            LocalVpnService.stop(this)
            return
        }
        if (prefs.overlayOn && !Settings.canDrawOverlays(this)) {
            pendingStart = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri(),
            )
            overlayPermission.launch(intent)
            return
        }
        armVpn()
    }

    private fun armVpn() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            pendingToggle = true
            vpnPrepare.launch(prepare)
        } else {
            LocalVpnService.start(this)
            OverlayService.arm(this)
        }
    }

    private fun ensureOverlay() {
        if (Settings.canDrawOverlays(this)) {
            OverlayService.start(this)
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri(),
        )
        overlayPermission.launch(intent)
    }

    private fun bindOverlay() {
        val allowed = Settings.canDrawOverlays(this)
        binding.overlaySwitch.setOnCheckedChangeListener(null)
        binding.overlaySwitch.isChecked = prefs.overlayOn && allowed
        binding.overlaySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.overlayOn = checked
            if (checked) ensureOverlay() else OverlayService.stop(this)
        }
        if (prefs.overlayOn && allowed) OverlayService.start(this)
    }

    private fun renderTimer() {
        val seconds = prefs.timerTenths / 10f
        binding.timerValue.text = getString(R.string.timer_value, seconds)
        binding.timerHint.setText(if (prefs.timerEnabled) R.string.timer_hint_on else R.string.timer_hint_off)
        val enabled = prefs.timerEnabled
        binding.timerSeek.isEnabled = enabled
        binding.timerValue.alpha = if (enabled) 1f else 0.4f
    }

    private fun renderVpn(on: Boolean) {
        binding.statusLabel.text = getString(if (on) R.string.vpn_on else R.string.vpn_off)
        binding.statusHint.text = getString(if (on) R.string.vpn_on_hint else R.string.vpn_off_hint)
        binding.orb.setBackgroundResource(if (on) R.drawable.bg_orb_on else R.drawable.bg_orb_off)
        binding.orbIcon.setImageResource(R.drawable.ic_shield)
        binding.orbIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (on) R.color.on else R.color.muted),
        )
        binding.statusLabel.setTextColor(getColor(if (on) R.color.on else R.color.fg))
    }

    private fun renderTargets() {
        val n = prefs.targetPackages().size
        binding.targetsCount.text = getString(R.string.targets_count, n)
        binding.targetsHint.text = if (n == 0) getString(R.string.targets_none) else getString(R.string.targets_hint)
    }
}
