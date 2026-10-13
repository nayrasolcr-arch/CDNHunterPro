package com.cdhunter.pro

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.cdhunter.pro.di.AppContainer
import com.cdhunter.pro.ui.AppNav
import com.cdhunter.pro.ui.theme.CdnHunterTheme
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    lateinit var container: AppContainer
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as CdnHunterApp).container

        // Apply persisted language (fa/en) via per-app locales.
        lifecycleScope.launch {
            container.settings.load()
            val lang = container.settings.language()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
        }

        setContent {
            val settings by container.settings.all.collectAsState()
            CdnHunterTheme(darkThemeOverride = settings["theme"]) {
                AppNav(container = container)
            }
        }
    }
}
