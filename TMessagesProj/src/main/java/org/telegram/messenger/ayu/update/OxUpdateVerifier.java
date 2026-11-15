package org.telegram.messenger.ayu.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

final class OxUpdateVerifier {
    static long installedVersion(Context context) throws PackageManager.NameNotFoundException {
        return version(context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
    }

    @SuppressWarnings("deprecation")
    static void verify(Context context, File file, OxUpdateManifest manifest, OxUpdateIo.Cancellation cancellation) throws Exception {
        OxUpdateIo.verifyHash(file, manifest, cancellation);
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
        PackageInfo candidate = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        cancellation.check();
        if (candidate == null || !context.getPackageName().equals(candidate.packageName)
                || !manifest.packageName.equals(candidate.packageName)
                || !manifest.version.equals(candidate.versionName)
                || version(candidate) != manifest.versionCode || version(candidate) <= version(installed)
                || candidate.applicationInfo == null
                || (Build.VERSION.SDK_INT >= 24 && candidate.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)) {
            throw new OxUpdateIo.Failure(OxUpdateIo.Kind.INVALID_APK);
        }
        Set<Signature> currentSigners = signers(installed);
        Set<Signature> newSigners = signers(candidate);
        // Require exactly the currently installed signers. A release cannot silently rotate the key.
        if (currentSigners.isEmpty() || !currentSigners.equals(newSigners)) {
            throw new OxUpdateIo.Failure(OxUpdateIo.Kind.INVALID_APK);
        }
    }

    @SuppressWarnings("deprecation")
    private static Set<Signature> signers(PackageInfo info) {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            signatures = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        } else {
            signatures = info.signatures;
        }
        Set<Signature> result = new HashSet<>();
        if (signatures != null) {
            for (Signature signature : signatures) {
                if (signature == null) return new HashSet<>();
                result.add(signature);
            }
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    private static long version(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }
}
