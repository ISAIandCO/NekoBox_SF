package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.fmt.warp.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import moe.matsuri.nb4a.utils.JavaUtil.gson

class WarpSettingsActivity : ThemedActivity() {
    private lateinit var content: LinearLayout
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_SHORTCUT)
    companion object {
        // Survives rotation; a second screen cannot register another device mid-request.
        private val busy = MutableStateFlow(false)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MaterialToolbar(this).apply {
            setTitle(R.string.warp_title)
            setNavigationIcon(R.drawable.baseline_arrow_back_24)
            setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { finish() }
        }, ViewGroup.LayoutParams(-1, -2))
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        lifecycleScope.launch { busy.collect { render() } }
        connection.connect(this, null)
    }

    override fun onDestroy() {
        connection.disconnect(this)
        super.onDestroy()
    }

    private fun request(action: String, config: WarpConfig? = null, amnezia: Boolean = false): String {
        val service = connection.service ?: error(getString(R.string.warp_service_unavailable))
        val result = service.warpOperation(action, config?.let { gson.toJson(it.identity) } ?: "", amnezia)
        val response = org.json.JSONObject(result)
        if (response.has("error")) error(response.getString("error"))
        return result
    }

    private fun changed() {
        render()
        if (DataStore.serviceState.started) SagerNet.reloadService(true)
    }

    private fun button(parent: LinearLayout, title: Int, action: () -> Unit) {
        parent.addView(MaterialButton(this).apply {
            setText(title)
            isEnabled = !busy.value
            setOnClickListener { action() }
        }, ViewGroup.LayoutParams(-1, -2))
    }

    private fun render() {
        content.removeAllViews()
        content.addView(TextView(this).apply { setText(R.string.warp_summary) })
        content.addView(MaterialSwitch(this).apply {
            setText(R.string.warp_enable)
            isChecked = DataStore.warpEnabled
            isEnabled = !busy.value && WarpConfigs.all().any { it.id == DataStore.warpSelected }
            setOnCheckedChangeListener { _, enabled ->
                DataStore.warpEnabled = enabled
                changed()
            }
        })
        if (busy.value) content.addView(TextView(this).apply { setText(R.string.warp_busy) })
        button(content, R.string.warp_generate) { generate() }
        for (config in WarpConfigs.all()) {
            val card = MaterialCardView(this)
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
            body.addView(TextView(this).apply {
                text = "${config.name}\n${if (config.amnezia) "AmneziaWG" else "WireGuard"} · ${config.identity.server}:${config.identity.port}"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            })
            body.addView(MaterialSwitch(this).apply {
                setText(R.string.warp_selected)
                isChecked = DataStore.warpSelected == config.id
                isEnabled = !busy.value
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        DataStore.warpSelected = config.id
                        changed()
                    } else render()
                }
            })
            button(body, R.string.warp_refresh) {
                operation(reloadExit = config.id) {
                    val identity = gson.fromJson(request("refresh", config), WarpIdentity::class.java)
                    WarpConfigs.save(config.copy(identity = identity))
                }
            }
            button(body, R.string.warp_add_profile) {
                operation {
                    ProfileManager.createProfile(DataStore.selectedGroupForImport(), config.toBean())
                }
            }
            button(body, R.string.warp_rename) {
                val input = TextInputEditText(this).apply { setText(config.name); setSingleLine() }
                MaterialAlertDialogBuilder(this).setTitle(R.string.warp_rename).setView(input)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isNotEmpty()) { WarpConfigs.save(config.copy(name = name)); render() }
                    }.show()
            }
            button(body, R.string.warp_delete) {
                if (config.id == DataStore.warpSelected && DataStore.warpEnabled) {
                    Toast.makeText(this, R.string.warp_disable_first, Toast.LENGTH_LONG).show()
                } else MaterialAlertDialogBuilder(this).setTitle(R.string.warp_delete)
                    .setMessage(R.string.warp_delete_summary)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setNeutralButton(R.string.warp_local_delete) { _, _ -> WarpConfigs.delete(config.id); render() }
                    .setPositiveButton(R.string.warp_revoke) { _, _ ->
                        operation {
                            request("revoke", config)
                            WarpConfigs.delete(config.id)
                        }
                    }.show()
            }
            card.addView(body)
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
    }

    private fun generate() {
        if (!DataStore.warpTermsAccepted) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.warp_title)
                .setMessage(R.string.warp_terms)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.warp_accept) { _, _ ->
                    DataStore.warpTermsAccepted = true
                    generate()
                }.show()
            return
        }
        MaterialAlertDialogBuilder(this).setTitle(R.string.warp_generate)
            .setItems(arrayOf("WireGuard", "AmneziaWG")) { _, index ->
                operation {
                    val identity = gson.fromJson(request("register", amnezia = index == 1), WarpIdentity::class.java)
                    val name = "WARP ${if (index == 1) "AmneziaWG" else "WireGuard"} ${identity.id.take(8)}"
                    val config = WarpConfig(name = name, amnezia = index == 1, identity = identity)
                    config.toBean() // Validate before saving, without changing an existing identity.
                    WarpConfigs.save(config)
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun operation(reloadExit: String? = null, action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        lifecycleScope.launch {
            try {
                // Persist a completed registration even if the screen is rotated.
                withContext(Dispatchers.IO + NonCancellable) {
                    action()
                    if (reloadExit != null && DataStore.warpEnabled && DataStore.warpSelected == reloadExit && DataStore.serviceState.started) {
                        SagerNet.reloadService(true)
                    }
                }
                if (isActive) render()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (isActive) MaterialAlertDialogBuilder(this@WarpSettingsActivity)
                    .setTitle(R.string.warp_error).setMessage(e.localizedMessage ?: e.javaClass.simpleName)
                    .setPositiveButton(android.R.string.ok, null).show()
            } finally { busy.value = false }
        }
    }
}
