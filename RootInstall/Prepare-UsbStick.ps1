<#
.SYNOPSIS
    Готовит флешку для установки coorlay с root на ГУ Geely Coolray (см. ROOT-INSTALL.md).

.EXAMPLE
    .\Prepare-UsbStick.ps1 -Drive E:
    Копирует установщик и самый новый coorlay-*.apk из папки Release на флешку E:.

.EXAMPLE
    .\Prepare-UsbStick.ps1 -Drive E: -Apk ..\..\coorlay-1.0.0.apk

.EXAMPLE
    .\Prepare-UsbStick.ps1 -Drive E: -Mode WifiDirect
    Только исправление Wi-Fi Direct: coorlay не устанавливается и не обновляется, APK на флешку не кладётся.

.EXAMPLE
    .\Prepare-UsbStick.ps1 -Drive E: -Mode Restore
    Вернуть исходный p2p_supplicant.conf (отменить исправление Wi-Fi Direct) и убрать файл Wi-Fi Direct, если его
    добавил coorlay (-Mode WifiDirectEnable); coorlay не трогается.

.EXAMPLE
    .\Prepare-UsbStick.ps1 -Drive E: -Mode WifiDirectEnable
    Для прошивок без Wi-Fi Direct (05.07.06000.H50): добавить android.hardware.wifi.direct.xml в /system и исправить
    Wi-Fi Direct. Пишет в /system; действует после выключения и включения зажигания. Отмена — -Mode Restore.

.EXAMPLE
    .\Prepare-UsbStick.ps1 -Drive E: -Mode Dump
    Только скопировать штатные KC2_popview, KC2_btphoneApp, KC2_btphone и /system/framework на флешку
    (coorlay\dump, около 90 МБ). На магнитоле ничего не меняется.
#>
param(
    [Parameter(Mandatory = $true)][string]$Drive,
    # Full — установить/обновить coorlay и исправить Wi-Fi Direct; WifiDirect — только исправление;
    # Restore — только вернуть исходную настройку Wi-Fi Direct; Dump — только копии штатных приложений на флешку;
    # WifiDirectEnable — добавить в прошивку файл Wi-Fi Direct (H50) и исправить Wi-Fi Direct.
    [ValidateSet('Full', 'WifiDirect', 'Restore', 'Dump', 'WifiDirectEnable')][string]$Mode = 'Full',
    [string]$Apk,
    # Переименовать чужую папку-триггер (например, от CommunityFull) вместо остановки.
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
$entry = 'b832bc61472727635baffcf25dd28e9f239273e2'
$entryScript = 'd4ad0e797c738a44a9dd1b9231d201e8374dfda2.sh'
$source = Join-Path $PSScriptRoot 'usb'

$letter = $Drive.TrimEnd(':', '\')
if ($letter.Length -ne 1) { throw "Укажите букву диска флешки, например -Drive E:" }
$volume = Get-Volume -DriveLetter $letter
if ($volume.DriveType -ne 'Removable') { throw "$($letter): не съёмный диск ($($volume.DriveType)). Укажите флешку." }
if ($volume.FileSystem -ne 'FAT32') { Write-Warning "Файловая система $($volume.FileSystem); ГУ надёжнее всего читает FAT32." }
$root = "$($letter):\"

$installApp = $Mode -eq 'Full'
if ($Apk -and -not $installApp) { throw "-Apk нужен только для -Mode Full" }
if ($installApp -and -not $Apk) {
    $Apk = Get-ChildItem (Join-Path $PSScriptRoot '..') -Filter 'coorlay-*.apk' |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
    if (-not $Apk) { throw "Не найден coorlay-*.apk в папке Release; укажите -Apk <путь>" }
}
if ($installApp) { $Apk = (Resolve-Path $Apk).Path }

$target = Join-Path $root $entry
if (Test-Path $target) {
    $theirs = Join-Path $target $entryScript
    # Any version of coorlay's own entry script is replaced; a foreign one (CommunityFull) needs -Force.
    $same = (Test-Path $theirs) -and ((Get-Content $theirs -Raw) -match 'coorlay root installer')
    if (-not $same) {
        if (-not $Force) {
            throw "На флешке уже есть другая папка $entry (например, CommunityFull). Возьмите отдельную флешку или запустите с -Force: старая папка будет переименована."
        }
        $old = "$entry-old-$(Get-Date -Format yyyyMMdd-HHmmss)"
        Rename-Item $target $old
        Write-Host "Чужая папка-триггер переименована в $old"
    }
}
$used = Join-Path $root "_$entry"
if (Test-Path $used) { Remove-Item $used -Recurse -Force }

Copy-Item (Join-Path $source '*') $root -Recurse -Force
$coorlay = Join-Path $root 'coorlay'
Get-ChildItem $coorlay -Filter '*.apk' | Remove-Item -Force
if ($installApp) { Copy-Item $Apk $coorlay }

# install.conf: what install.sh does on this run.
$settings = @{
    'Full'             = @{ INSTALL_APP = 1; FIX_WIFI_DIRECT = 1; RESTORE_WIFI_DIRECT = 0; ENABLE_WIFI_DIRECT_FEATURE = 0; DUMP_SYSTEM_APPS = 0 }
    'WifiDirect'       = @{ INSTALL_APP = 0; FIX_WIFI_DIRECT = 1; RESTORE_WIFI_DIRECT = 0; ENABLE_WIFI_DIRECT_FEATURE = 0; DUMP_SYSTEM_APPS = 0 }
    'Restore'          = @{ INSTALL_APP = 0; FIX_WIFI_DIRECT = 0; RESTORE_WIFI_DIRECT = 1; ENABLE_WIFI_DIRECT_FEATURE = 0; DUMP_SYSTEM_APPS = 0 }
    'Dump'             = @{ INSTALL_APP = 0; FIX_WIFI_DIRECT = 0; RESTORE_WIFI_DIRECT = 0; ENABLE_WIFI_DIRECT_FEATURE = 0; DUMP_SYSTEM_APPS = 1 }
    'WifiDirectEnable' = @{ INSTALL_APP = 0; FIX_WIFI_DIRECT = 1; RESTORE_WIFI_DIRECT = 0; ENABLE_WIFI_DIRECT_FEATURE = 1; DUMP_SYSTEM_APPS = 0 }
}[$Mode]
$conf = Join-Path $coorlay 'install.conf'
$lines = (Get-Content (Join-Path $source 'coorlay\install.conf')) | ForEach-Object {
    if ($_ -match '^(\w+)=' -and $settings.ContainsKey($Matches[1])) { "$($Matches[1])=$($settings[$Matches[1]])" } else { $_ }
}
[IO.File]::WriteAllText($conf, ($lines -join "`n") + "`n", (New-Object Text.UTF8Encoding $false))

Write-Host ""
$what = @{
    'Full'             = 'установка/обновление coorlay и исправление Wi-Fi Direct'
    'WifiDirect'       = 'только исправление Wi-Fi Direct (coorlay не трогается)'
    'Restore'          = 'только возврат исходной настройки Wi-Fi Direct (coorlay не трогается)'
    'Dump'             = 'только копии штатных popview/btphoneApp/btphone и framework в coorlay\dump (магнитола не меняется)'
    'WifiDirectEnable' = 'добавление файла Wi-Fi Direct в прошивку (H50) и исправление Wi-Fi Direct; после — выключить и включить зажигание'
}[$Mode]
Write-Host "Флешка $root готова: $what"
Write-Host "  $entry\$entryScript  — запускается прошивкой от root"
Write-Host "  coorlay\install.sh, install.conf — установщик и его настройки"
if ($installApp) { Write-Host "  coorlay\$(Split-Path $Apk -Leaf)" }
Write-Host "Журнал появится в coorlay\logs. Порядок установки — в ROOT-INSTALL.md."
