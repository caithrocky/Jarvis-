package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.security.SecretsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("JARVIS AI", appName)
    }

    @Test
    fun `placeholder secrets are treated as unconfigured`() {
        // Default .env.example placeholders start with MY_ and must not be treated as live keys
        if (BuildConfig.NVIDIA_NIM_API_KEY.startsWith("MY_")) {
            assertFalse(SecretsConfig.hasNvidiaNim)
        }
        if (BuildConfig.BINANCE_API_KEY.startsWith("MY_")) {
            assertFalse(SecretsConfig.hasBinanceAuth)
        }
    }

    @Test
    fun `secrets normalization strips bearer prefix, quotes, and fixes supabase url`() {
        assertEquals("abc123token", SecretsConfig.cleanSecret("  \"Bearer abc123token\n\"  "))
        assertNull(SecretsConfig.cleanSecret("MY_DERIV_API_TOKEN"))
        assertNull(SecretsConfig.cleanSecret("your_api_key_here"))

        assertEquals(
            "https://myproj.supabase.co",
            SecretsConfig.normalizeHttpsUrl("myproj.supabase.co/rest/v1/")
        )
        assertEquals(
            "https://myproj.supabase.co",
            SecretsConfig.normalizeHttpsUrl("myproj")
        )
    }
}
