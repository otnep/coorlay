@echo off
rem Prepares a USB stick for firmware without Wi-Fi Direct (05.07.06000.H50): adds the Wi-Fi Direct feature file
rem to /system and fixes Wi-Fi Direct; coorlay is not touched. Turn the ignition off and on afterwards.
rem PowerShell asks for the drive letter (e.g. E:). See ROOT-INSTALL.md.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Prepare-UsbStick.ps1" -Mode WifiDirectEnable %*
pause
