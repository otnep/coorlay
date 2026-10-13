@echo off
rem Prepares a USB stick: Wi-Fi Direct fix only, coorlay is not touched.
rem PowerShell asks for the drive letter (e.g. E:). See ROOT-INSTALL.md.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Prepare-UsbStick.ps1" -Mode WifiDirect %*
pause
