package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

public class LauncherIconController {
    public static void tryFixLauncherIconIfNeeded() {
        for (LauncherIcon icon : LauncherIcon.values()) {
            if (isEnabled(icon)) {
                return;
            }
        }

        setIcon(LauncherIcon.DEFAULT);
    }

    public static boolean isEnabled(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        int i = ctx.getPackageManager().getComponentEnabledSetting(icon.getComponentName(ctx));
        return i == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || i == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == LauncherIcon.DEFAULT;
    }

    public static void setIcon(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        PackageManager pm = ctx.getPackageManager();
        for (LauncherIcon i : LauncherIcon.values()) {
            pm.setComponentEnabledSetting(i.getComponentName(ctx), i == icon ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }

    public enum LauncherIcon {
        DEFAULT("DefaultIcon", R.drawable.icon_background_sa, R.mipmap.icon_foreground, R.string.AppIconDefault),
        CRIMSON("CrimsonIcon", R.drawable.icon_crimson_background, R.mipmap.icon_foreground, R.string.AppIconCrimson),
        EMERALD("EmeraldIcon", R.drawable.icon_emerald_background, R.mipmap.icon_foreground, R.string.AppIconEmerald),
        AURORA("AuroraIcon", R.mipmap.icon_aurora_background, R.mipmap.icon_foreground, R.string.AppIconAurora),
        NEON_CRIMSON("NeonCrimsonIcon", R.drawable.icon_neon_background, R.mipmap.icon_neon_crimson_foreground, R.string.AppIconNeonCrimson),
        NEON_EMERALD("NeonEmeraldIcon", R.drawable.icon_neon_background, R.mipmap.icon_neon_emerald_foreground, R.string.AppIconNeonEmerald),
        NEON_AZURE("NeonAzureIcon", R.drawable.icon_neon_background, R.mipmap.icon_neon_azure_foreground, R.string.AppIconNeonAzure),
        NEON_AURORA("NeonAuroraIcon", R.drawable.icon_neon_background, R.mipmap.icon_neon_aurora_foreground, R.string.AppIconNeonAurora);

        public final String key;
        public final int background;
        public final int foreground;
        public final int title;
        public final boolean premium;

        private ComponentName componentName;

        public ComponentName getComponentName(Context ctx) {
            if (componentName == null) {
                componentName = new ComponentName(ctx.getPackageName(), "org.telegram.messenger." + key);
            }
            return componentName;
        }

        LauncherIcon(String key, int background, int foreground, int title) {
            this(key, background, foreground, title, false);
        }

        LauncherIcon(String key, int background, int foreground, int title, boolean premium) {
            this.key = key;
            this.background = background;
            this.foreground = foreground;
            this.title = title;
            this.premium = premium;
        }
    }
}
