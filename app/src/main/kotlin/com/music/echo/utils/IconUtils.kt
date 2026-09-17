

package echo.music.iad1tya.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

enum class AppIconType(val value: Int) {
    DEFAULT(0),
    LEGACY(1),
    STATIC(2)
}

object IconUtils {
    fun setIcon(context: Context, iconType: AppIconType) {
        val pm = context.packageManager
        val dynamic = ComponentName(context, "echo.music.iad1tya.MainActivityAlias")
        val static = ComponentName(context, "echo.music.iad1tya.MainActivityStatic")
        val legacy = ComponentName(context, "echo.music.iad1tya.MainActivityLegacy")

        pm.setComponentEnabledSetting(
            dynamic,
            if (iconType == AppIconType.DEFAULT) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        pm.setComponentEnabledSetting(
            legacy,
            if (iconType == AppIconType.LEGACY) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        pm.setComponentEnabledSetting(
            static,
            if (iconType == AppIconType.STATIC) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
