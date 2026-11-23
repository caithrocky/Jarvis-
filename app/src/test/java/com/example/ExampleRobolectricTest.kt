package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.security.SecretsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
