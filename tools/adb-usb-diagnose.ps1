<#
    USB / adb connection diagnostic for the DSH Phone Agent.

    Run this with the phone plugged in. It reports what Windows actually sees,
    which is the difference between "the cable never carried data" and "the driver
    is missing" — two problems that look identical from adb's side (both show an
    empty device list) but need completely different fixes.

        powershell -ExecutionPolicy Bypass -File tools\adb-usb-diagnose.ps1
#>

$ErrorActionPreference = "Continue"

$adb = "C:\Users\you\.dsh\profiles\web\node_modules\dsh-coremate-mobile\assets\platform-tools\win32-x64\adb.exe"
if (-not (Test-Path $adb)) {
    $found = Get-Command adb -ErrorAction SilentlyContinue
    $adb = if ($found) { $found.Source } else { $null }
}

Write-Output "=========== 1. adb ==========="
if ($adb) {
    Write-Output "adb: $adb"
    & $adb kill-server 2>&1 | Out-Null
    & $adb start-server 2>&1 | Out-Null
    $devices = & $adb devices -l 2>&1
    Write-Output $devices
} else {
    Write-Output "adb 未找到"
}

Write-Output ""
Write-Output "=========== 2. Windows 看到的 USB 设备 ==========="
$usb = Get-PnpDevice -PresentOnly -Class USB -ErrorAction SilentlyContinue
$usb | Select-Object Status, FriendlyName, InstanceId | Format-Table -AutoSize -Wrap

Write-Output "=========== 3. 有问题的设备(枚举失败 / 缺驱动) ==========="
$broken = $usb | Where-Object { $_.Status -ne "OK" }
if ($broken) {
    $broken | ForEach-Object {
        $problem = (Get-PnpDevice -InstanceId $_.InstanceId -ErrorAction SilentlyContinue |
                    Select-Object -ExpandProperty ProblemDescription) 2>$null
        Write-Output "  状态 : $($_.Status)"
        Write-Output "  名称 : $($_.FriendlyName)"
        Write-Output "  ID   : $($_.InstanceId)"
        Write-Output "  说明 : $problem"
        Write-Output ""
    }
} else {
    Write-Output "  没有异常设备"
}

Write-Output "=========== 4. 结论 ==========="
$hasRealPhone = $usb | Where-Object { $_.InstanceId -match "VID_2717|VID_18D1|VID_2A70|VID_12D1|VID_04E8" }
$hasDescriptorFailure = $usb | Where-Object { $_.InstanceId -match "VID_0000" }
$deviceListed = ($devices -join "`n") -match "\tdevice"

if ($deviceListed) {
    Write-Output "  ✅ adb 已识别到设备 —— 可以直接安装 APK"
} elseif ($hasRealPhone) {
    Write-Output "  ⚠️ Windows 认到了手机(有正常 VID),但 adb 没连上。"
    Write-Output "     多半是:手机上的「允许 USB 调试」弹窗没确认,或 USB 用途不是文件传输。"
    Write-Output "     也可能是缺 ADB 驱动 —— 设备管理器中该设备应显示为 Android ADB Interface。"
} elseif ($hasDescriptorFailure) {
    Write-Output "  ❌ 设备描述符请求失败(VID_0000)。"
    Write-Output "     这不是驱动问题,是 USB 链路本身没建立。按顺序试:"
    Write-Output "       1. 换一根确定能传数据的线(很多附赠线只有充电线芯)"
    Write-Output "       2. 插到主机【后面板】USB 口,不要用扩展坞 / 显示器 Hub"
    Write-Output "       3. 清理手机充电口,换个方向重插"
    Write-Output "       4. 换一台电脑试,以区分是手机口还是电脑口的问题"
} else {
    Write-Output "  ❌ 没有看到任何像手机的 USB 设备。"
    Write-Output "     说明插上后连枚举都没发生 —— 大概率是线或手机接口。"
    Write-Output "     换线、换口、确认手机端出现「正在通过 USB 充电」提示。"
}

Write-Output ""
Write-Output "提示:插拔手机时若要观察变化,可另开一个窗口运行"
Write-Output '  while($true){ Get-PnpDevice -PresentOnly -Class USB | Select Status,FriendlyName,InstanceId | Format-Table -AutoSize; Start-Sleep 5; Clear-Host }'
