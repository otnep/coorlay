@echo off
rem Prepares a USB stick: coorlay install/update + Wi-Fi Direct fix.
rem PowerShell asks for the drive letter (e.g. E:). See ROOT-INSTALL.md.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Prepare-UsbStick.ps1" -Mode Full %*
pause
