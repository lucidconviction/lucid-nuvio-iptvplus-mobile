package com.nuvio.app.features.plugins.external

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts a CloudStream extension's AppCompat Fragment/Dialog settings above the
 * provider list. Mirrors ReSeq's host: load the callback off the main thread,
 * publish the Activity through the bridge globals, and finish when the plugin's
 * last fragment is destroyed.
 */
class ExtensionSettingsActivity : AppCompatActivity() {
    private var previousCommon: Activity? = null
    private var previousAcra: Activity? = null
    private var pending: ((Context) -> Unit)? = null
    private var callbackStarted = false
    private var dialogTookFocus = false

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
            window.decorView.post {
                if (!isDestroyed && !isFinishing && !isChangingConfigurations &&
                    fm.fragments.none { !it.isRemoving }
                ) {
                    finish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Plugin fragments may require constructor arguments and a DEX class loader.
        // Reopen via the plugin instead of asking Android to restore an old fragment.
        super.onCreate(null)
        val id = intent.getStringExtra(EXTRA_ID)
        val profileId = intent.getIntExtra(EXTRA_PROFILE_ID, 1)
        if (id == null) {
            finish()
            return
        }
        supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, false)
        lifecycleScope.launch {
            try {
                pending = withContext(Dispatchers.IO) {
                    AndroidExternalExtensionHost.settingsCallback(id, profileId)
                }
                if (hasWindowFocus()) showPendingSettings()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failed(e)
            }
        }
    }

    private fun showPendingSettings() {
        val callback = pending ?: return
        pending = null
        previousCommon = CommonActivity.activity
        previousAcra = AcraApplication.getActivity()
        CommonActivity.activity = this
        AcraApplication.setActivity(this)
        callbackStarted = true
        try {
            callback(this)
            supportFragmentManager.executePendingTransactions()
        } catch (e: Throwable) {
            failed(e)
        }
    }

    private fun failed(e: Throwable) {
        if (e is VirtualMachineError || e is ThreadDeath) throw e
        if (e is LinkageError) {
            Log.w("ExtensionSettings", "Settings callback linkage failure", e)
        } else {
            Log.w("ExtensionSettings", "Settings callback failed: ${e.javaClass.simpleName}")
        }
        Toast.makeText(this, "This extension could not open its settings.", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pending != null) {
            showPendingSettings()
            return
        }
        if (!callbackStarted) return
        if (!hasFocus) {
            dialogTookFocus = true
        } else if (dialogTookFocus && supportFragmentManager.fragments.isEmpty()) {
            finish()
        }
    }

    override fun onDestroy() {
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragments)
        if (CommonActivity.activity === this) {
            CommonActivity.activity = previousCommon?.takeUnless { it.isDestroyed }
        }
        if (AcraApplication.getActivity() === this) {
            AcraApplication.setActivity(previousAcra?.takeUnless { it.isDestroyed })
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "scraper_id"
        private const val EXTRA_PROFILE_ID = "profile_id"

        fun open(activity: Activity, scraperId: String, profileId: Int) {
            activity.startActivity(
                Intent(activity, ExtensionSettingsActivity::class.java)
                    .putExtra(EXTRA_ID, scraperId)
                    .putExtra(EXTRA_PROFILE_ID, profileId),
            )
        }
    }
}
