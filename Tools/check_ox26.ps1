$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root 'build/ox26-regressions'
[IO.Directory]::CreateDirectory($out) | Out-Null
function Read-Source($relative) { [IO.File]::ReadAllText((Join-Path $root $relative)) }
function Get-Method($source, $signature) {
    $start = $source.IndexOf($signature, [StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing method: $signature" }
    $open = $source.IndexOf('{', $start)
    $depth = 1
    $end = $open + 1
    while ($depth -gt 0 -and $end -lt $source.Length) {
        if ($source[$end] -eq '{') { $depth++ }
        if ($source[$end] -eq '}') { $depth-- }
        $end++
    }
    if ($depth) { throw "Unclosed method: $signature" }
    $source.Substring($start, $end - $start)
}
$user = Read-Source 'TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java'
$message = Read-Source 'TMessagesProj/src/main/java/org/telegram/messenger/MessageObject.java'
$methods = @(
    'public static UserConfig getInstance(int num)',
    'public static int getMaxAccountCount()',
    'public static boolean isValidAccountIndex(int account)',
    'public static int findFreeAccount()',
    'public static int getActivatedAccountsCount()',
    'public static boolean isValidAccount(int num)'
) | ForEach-Object { Get-Method $user $_ }
$constants = ([regex]::Matches($user, 'public final static int MAX_ACCOUNT_\w+ = [^;]+;') | ForEach-Object Value) -join "`n"
$harness = @'
import java.util.*;
public class Ox26Checks {
    static int checks;
    static void check(boolean ok) { if (!ok) throw new AssertionError("check " + checks); checks++; }
    static class UserConfig {
        static UserConfig[] Instance = new UserConfig[25];
        boolean active;
        final int account;
        UserConfig(int a) { account = a; }
        boolean isClientActivated() { return active; }
        CONSTANTS
        METHODS
    }
    static class AccountInstance {
        final int account;
        AccountInstance(int a) { account = a; }
        static AccountInstance getInstance(int a) { return new AccountInstance(a); }
        UserConfig getUserConfig() { return UserConfig.getInstance(account); }
    }
    SANITIZER
    public static void main(String[] args) throws Exception {
        check(UserConfig.MAX_ACCOUNT_COUNT == 25);
        for (int count : new int[]{0, 3, 4, 5, 24, 25}) {
            for (int a = 0; a < 25; a++) UserConfig.getInstance(a).active = a < count;
            check(UserConfig.getActivatedAccountsCount() == count);
            check(UserConfig.findFreeAccount() == (count == 25 ? -1 : count));
            check(UserConfig.getMaxAccountCount() == 25);
        }
        check(UserConfig.getInstance(24).account == 24);
        check(UserConfig.isValidAccount(24));
        for (int invalid : new int[]{-1, 25, Integer.MAX_VALUE}) {
            check(!UserConfig.isValidAccountIndex(invalid));
            check(!UserConfig.isValidAccount(invalid));
            try { UserConfig.getInstance(invalid); throw new AssertionError("accepted invalid account"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        Set<UserConfig> instances = Collections.synchronizedSet(new HashSet<>());
        UserConfig.Instance[24] = null;
        ArrayList<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            Thread thread = new Thread(() -> instances.add(UserConfig.getInstance(24)));
            threads.add(thread); thread.start();
        }
        for (Thread thread : threads) thread.join();
        check(instances.size() == 1);
        check(sanitizeCorruptedMessage(null) == null);
        for (String text : new String[]{"", "Salom", "audio/ogg", "https://org.telegram.org",
                "/storage/emulated/0/Android/data/example", "\u0421\u0430\u043b\u043e\u043c", "hello \ud83d\ude00", "a\nb\rc\td"}) {
            check(text.equals(sanitizeCorruptedMessage(text)));
        }
        check("abc".equals(sanitizeCorruptedMessage("a\u0000b\u0001c")));
        System.out.println("PASS " + checks + " actual-source account and message regressions");
    }
}
'@
$harness = $harness.Replace('CONSTANTS', $constants).Replace('METHODS', ($methods -join "`n"))
$harness = $harness.Replace('SANITIZER', (Get-Method $message 'public static String sanitizeCorruptedMessage(String msg)'))
$sourcePath = Join-Path $out 'Ox26Checks.java'
[IO.File]::WriteAllText($sourcePath, $harness, [Text.UTF8Encoding]::new($false))
& javac -encoding UTF-8 -d $out $sourcePath
if ($LASTEXITCODE -ne 0) { throw 'Regression harness compilation failed' }
& java -cp $out Ox26Checks
if ($LASTEXITCODE -ne 0) { throw 'Regression checks failed' }

# Compile the actual native account factory for Android, including the boundary guard.
$native = Read-Source 'TMessagesProj/jni/tgnet/ConnectionsManager.cpp'
$defines = Read-Source 'TMessagesProj/jni/tgnet/Defines.h'
$count = [regex]::Match($defines, '#define MAX_ACCOUNT_COUNT (\d+)').Groups[1].Value
if ($count -ne '25') { throw 'Java/native account capacity mismatch' }
$nativeFixture = @'
#include <memory>
#include <mutex>
#include <cstdint>
#include <cstdlib>
#define MAX_ACCOUNT_COUNT 25
class ConnectionsManager {
public:
    explicit ConnectionsManager(int32_t) {}
    static ConnectionsManager& getInstance(int32_t);
};
'@
$nativeFixture += Get-Method $native 'ConnectionsManager& ConnectionsManager::getInstance(int32_t instanceNum)'
$nativePath = Join-Path $out 'account_factory.cpp'
[IO.File]::WriteAllText($nativePath, $nativeFixture, [Text.UTF8Encoding]::new($false))
$sdkProperties = Read-Source 'local.properties'
$sdk = ([regex]::Match($sdkProperties, '(?m)^sdk.dir=(.+)').Groups[1].Value.Trim()).Replace('\:', ':').Replace('\\', '\')
$clang = Join-Path $sdk 'ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
& $clang '--target=aarch64-linux-android21' '-std=c++17' -c $nativePath -o (Join-Path $out 'account_factory.o')
if ($LASTEXITCODE -ne 0) { throw 'Native Android account factory compilation failed' }

[xml]$paths = Read-Source 'TMessagesProj/src/main/res/xml/provider_paths.xml'
if ($paths.paths.'cache-path'.path -ne 'ox-updates/') { throw 'Update installer cannot access the APK cache' }
Write-Output 'PASS native Android factory compilation and update FileProvider mapping'
