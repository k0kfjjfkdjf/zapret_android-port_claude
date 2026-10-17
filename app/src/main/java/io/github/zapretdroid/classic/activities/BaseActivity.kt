package io.github.zapretdroid.classic.activities

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors
import io.github.zapretdroid.R
import io.github.zapretdroid.classic.utility.SettingsUtils
import io.github.zapretdroid.classic.utility.getPreferences
import io.github.zapretdroid.classic.utility.getStringNotNull

abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getPreferences()

        val lang = prefs.getStringNotNull("language", "system")
        SettingsUtils.setLang(lang)

        val theme = prefs.getStringNotNull("app_theme", "system")
        SettingsUtils.setTheme(theme)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicColors.applyToActivityIfAvailable(this)
        }

        super.onCreate(savedInstanceState)
    }

    protected fun setupToolbar() {
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
    }

}
