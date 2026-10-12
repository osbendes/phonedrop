package com.phonedrop.app

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * The language and light/dark theme chosen in Settings, applied by wrapping a component's base context.
 * Language: Turkish or English; "system" means Turkish on a Turkish phone and English on any other.
 *
 * TR: Ayarlar'da seçilen dil ve açık/koyu tema; bir bileşenin temel bağlamı sarılarak uygulanır.
 * Dil: Türkçe ya da İngilizce; "sistem", Türkçe telefonda Türkçe, diğer bütün dillerde İngilizce demektir.
 */
object Look {
    const val PREFS = "ui"
    const val LANG = "lang"    // "" (system), "en", "tr"
    const val THEME = "theme"  // "" (system), "light", "dark"

    /**
     * Only the values we override; everything else stays undefined so the system's own values apply.
     *
     * TR: Yalnızca bizim değiştirdiğimiz değerler; gerisi tanımsız kalır, böylece sistemin değerleri geçerli olur.
     */
    fun config(ctx: Context): Configuration {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cfg = Configuration()
        // Anything but an explicit choice means "system"
        val lang = sp.getString(LANG, "").takeIf { it == "en" || it == "tr" }
            ?: if (Resources.getSystem().configuration.locales[0].language == "tr") "tr" else "en"
        cfg.setLocale(Locale.forLanguageTag(lang))
        when (sp.getString(THEME, "")) {
            "light" -> cfg.uiMode = Configuration.UI_MODE_NIGHT_NO
            "dark" -> cfg.uiMode = Configuration.UI_MODE_NIGHT_YES
        }
        return cfg
    }

    /**
     * For services and receivers. Activities call `applyOverrideConfiguration(Look.config(base))` instead.
     *
     * TR: Servisler ve alıcılar için. Activity'ler bunun yerine `applyOverrideConfiguration(Look.config(base))`
     * çağırır.
     */
    fun wrap(base: Context): Context = base.createConfigurationContext(config(base))
}
