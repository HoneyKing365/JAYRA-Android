package ai.jayra.companion.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ai.jayra.companion.model.AudioRoutingMode
import ai.jayra.companion.model.SassyLanguage
import java.util.UUID

/** AES-256-GCM encrypted key/value storage backed by the Android Keystore. */
class SecureStore(private val context: Context) {

    private val prefs: SharedPreferences = create()

    private fun create(): SharedPreferences {
        fun build(): SharedPreferences {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
        return try {
            build()
        } catch (e: Exception) {
            // Corrupt keystore entry (e.g. after a restore) – start clean rather than crash.
            context.deleteSharedPreferences(FILE)
            build()
        }
    }

    var host: String?
        get() = prefs.getString(K_HOST, null)
        set(v) = prefs.edit().putString(K_HOST, v).apply()

    var port: Int
        get() = prefs.getInt(K_PORT, 3000)
        set(v) = prefs.edit().putInt(K_PORT, v).apply()

    var token: String?
        get() = prefs.getString(K_TOKEN, null)
        set(v) = prefs.edit().putString(K_TOKEN, v).apply()

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean(K_ONBOARDING, false)
        set(v) = prefs.edit().putBoolean(K_ONBOARDING, v).apply()

    var clipboardSync: Boolean
        get() = prefs.getBoolean(K_CLIPBOARD, false)
        set(v) = prefs.edit().putBoolean(K_CLIPBOARD, v).apply()

    var biometricGuard: Boolean
        get() = prefs.getBoolean(K_BIOMETRIC, true)
        set(v) = prefs.edit().putBoolean(K_BIOMETRIC, v).apply()

    var audioRouting: AudioRoutingMode
        get() = AudioRoutingMode.parse(prefs.getString(K_ROUTING, null))
        set(v) = prefs.edit().putString(K_ROUTING, v.name).apply()

    var sassyLanguage: SassyLanguage
        get() = SassyLanguage.parse(prefs.getString(K_SASSY, null))
        set(v) = prefs.edit().putString(K_SASSY, v.name).apply()

    val deviceId: String
        get() = prefs.getString(K_DEVICE, null) ?: ("dev_" + UUID.randomUUID().toString().take(8)).also {
            prefs.edit().putString(K_DEVICE, it).apply()
        }

    fun clearCredentials() {
        prefs.edit().remove(K_TOKEN).apply()
    }

    private companion object {
        const val FILE = "jayra_secure_prefs"
        const val K_HOST = "host"
        const val K_PORT = "port"
        const val K_TOKEN = "bridge_token"
        const val K_ONBOARDING = "jayra_onboarding_completed"
        const val K_CLIPBOARD = "clipboard_sync"
        const val K_BIOMETRIC = "biometric_guard"
        const val K_ROUTING = "audio_routing"
        const val K_SASSY = "sassy_language"
        const val K_DEVICE = "device_id"
    }
}
