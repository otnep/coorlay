#!/usr/bin/env bash
# Host regression test. Android commands are replaced with fixtures.
set -euo pipefail

installer=$(dirname "$0")/../usb/coorlay/install.sh
root=$(mktemp -d)
trap 'rm -r -- "$root"' EXIT

# Load the installer functions without its device checks and entry point.
source <(sed -n '/^ctrl_lines()/,/^wifi_hardware_info$/{ /^wifi_hardware_info$/!p; }' "$installer")

print() { shift 2; printf '%s\n' "$*"; }
say() { printf '%s\n' "$*" >> "$log"; }
sync() { :; }
stop_wifi() { echo stop >> "$wifi_calls"; }
start_wifi() { echo start >> "$wifi_calls"; }
await_socket() { :; }

settings() {
	[[ "$2" == global && "$3" == "$P2P_SCAN_SETTING" ]] || return 2
	case "$1" in
		get) if [[ -f "$interval" ]]; then cat "$interval"; else echo null; fi ;;
		put) if [[ ! -f "$failure" ]]; then printf '%s\n' "$4" > "$interval"; fi ;;
		*) return 2 ;;
	esac
}

content() {
	case "$*" in
		"query --uri content://settings/global --projection value --where name='$P2P_SCAN_SETTING'")
			if [[ -f "$interval" ]]; then echo "Row: 0 value=$(cat "$interval")"; else echo "No result found."; fi ;;
		"delete --uri content://settings/global --where name='$P2P_SCAN_SETTING'")
			if [[ ! -f "$failure" ]]; then rm "$interval"; fi ;;
		*) return 2 ;;
	esac
}

reset_fixture() {
	local path=$root/$1
	mkdir "$path"
	P2P_REAL=$path/actual-socket
	P2P_SOCKET=$path/p2p0
	P2P_CONF=$path/p2p.conf
	P2P_BACKUP=$path/p2p.conf.orig
	P2P_ABSENT=$path/p2p.conf.absent
	P2P_STATE=$path/state
	P2P_MARKER=$path/marker
	P2P_TEMPLATE=$path/template
	FEATURE_XML=$path/feature.xml
	log=$path/log
	wifi_calls=$path/wifi-calls
	interval=$path/interval
	failure=$path/failure
	: > "$log"
	: > "$wifi_calls"
	: > "$FEATURE_XML"
	printf '%s\n' "$P2P_BROKEN" > "$P2P_TEMPLATE"
}

VERSION=9
cr=$'\r'
P2P_BROKEN='ctrl_interface=DIR=/data/system/wpa_supplicant GROUP=wifi'
P2P_BROKEN_H50='ctrl_interface=/data/system/wpa_supplicant'
P2P_CTRL='ctrl_interface=DIR=/data/misc/wifi/sockets GROUP=wifi'
P2P_SCAN_SETTING=wifi_scan_interval_p2p_connected_ms
P2P_SCAN_INTERVAL=3600000
FEATURE_TAG='added by the coorlay root installer'

expect_failure() {
	if "$@"; then echo "Expected failure from $*" >&2; exit 1; fi
}

reset_fixture before-install
ln -s "$root/foreign-socket" "$P2P_SOCKET"
echo 19000 > "$interval"
restore_wifi_direct
[[ "$(readlink "$P2P_SOCKET")" == "$root/foreign-socket" && $(cat "$interval") == 19000 ]]
[[ ! -s "$wifi_calls" ]]
expect_failure fix_wifi_direct
[[ ! -e "$P2P_STATE" && $(cat "$interval") == 19000 ]]
[[ "$(readlink "$P2P_SOCKET")" == "$root/foreign-socket" ]]

reset_fixture repeat-install-and-undo
echo 19000 > "$interval"
fix_wifi_direct
[[ -L "$P2P_SOCKET" && $(cat "$interval") == 3600000 ]]
fix_wifi_direct
restore_wifi_direct
[[ ! -L "$P2P_SOCKET" && ! -e "$P2P_STATE" && ! -e "$P2P_MARKER" ]]
[[ $(cat "$interval") == 19000 ]]
: > "$wifi_calls"
restore_wifi_direct
[[ ! -s "$wifi_calls" && $(cat "$interval") == 19000 ]]

reset_fixture preexisting-matching-link-and-no-setting
ln -s "$P2P_REAL" "$P2P_SOCKET"
fix_wifi_direct
restore_wifi_direct
[[ -L "$P2P_SOCKET" && ! -e "$interval" && ! -e "$P2P_STATE" ]]

reset_fixture external-changes-after-install
fix_wifi_direct
rm "$P2P_SOCKET"
ln -s "$root/foreign-socket" "$P2P_SOCKET"
echo 90000 > "$interval"
restore_wifi_direct
[[ "$(readlink "$P2P_SOCKET")" == "$root/foreign-socket" && $(cat "$interval") == 90000 ]]
[[ ! -e "$P2P_STATE" ]]

reset_fixture failed-restore-can-be-retried
fix_wifi_direct
: > "$failure" # Android command exits successfully but fails to modify the setting.
expect_failure restore_wifi_direct
grep -q 'could not restore .*saved state retained' "$log"
[[ ! -L "$P2P_SOCKET" && $(cat "$interval") == 3600000 && -f "$P2P_STATE" ]]
rm "$failure"
restore_wifi_direct
[[ ! -e "$interval" && ! -e "$P2P_STATE" ]]

reset_fixture failed-apply-can-be-undone
echo 19000 > "$interval"
: > "$failure"
expect_failure fix_wifi_direct
[[ -f "$P2P_STATE" && -L "$P2P_SOCKET" && ! -f "$P2P_MARKER" ]]
restore_wifi_direct
[[ ! -f "$P2P_STATE" && ! -L "$P2P_SOCKET" && $(cat "$interval") == 19000 ]]

reset_fixture failed-link-removal-can-be-retried
echo 19000 > "$interval"
fix_wifi_direct
rm() {
	if [[ "$*" == "$P2P_SOCKET" ]]; then return 1; fi
	command rm "$@"
}
expect_failure restore_wifi_direct
unset -f rm
grep -q 'could not remove the coorlay link' "$log"
[[ -L "$P2P_SOCKET" && -f "$P2P_STATE" ]]
restore_wifi_direct
[[ ! -L "$P2P_SOCKET" && ! -f "$P2P_STATE" && $(cat "$interval") == 19000 ]]

reset_fixture literal-null-is-not-missing
echo null > "$interval"
expect_failure fix_wifi_direct
[[ $(cat "$interval") == null && ! -L "$P2P_SOCKET" && ! -e "$P2P_STATE" ]]

reset_fixture legacy-marker
echo 'fixed=20261118-120000 installer=8 method=link socket=ok feature=firmware' > "$P2P_MARKER"
ln -s "$P2P_REAL" "$P2P_SOCKET"
echo 3600000 > "$interval"
restore_wifi_direct
grep -q 'no saved socket or scan interval' "$log"
[[ -L "$P2P_SOCKET" && $(cat "$interval") == 3600000 && ! -f "$P2P_MARKER" ]]

reset_fixture apk-conflict
INSTALL_APP=1 FIX_WIFI_DIRECT=1 RESTORE_WIFI_DIRECT=0 ENABLE_WIFI_DIRECT_FEATURE=0 failed=0
install_app() { return 1; }
source <(sed -n '/^apk_failed=0$/,/^if \[ "\$RUN_ONCE"/{ /^if \[ "\$RUN_ONCE"/!p; }' "$installer")
[[ "$failed" == 1 && ! -s "$wifi_calls" && ! -f "$P2P_STATE" ]]
grep -q 'APK result: FAILED' "$log"
grep -q 'Wi-Fi Direct result: SKIPPED (APK installation failed)' "$log"

reset_fixture h50-archive-firmware-check
getprop() {
	case "$1" in
		ro.product.manufacturer) echo Neusoft ;;
		ro.product.model) echo GEELY ;;
		ro.product.board) echo C3ALFUS ;;
		ro.build.version.release) echo 4.3 ;;
		ro.build.id) echo "$firmware" ;;
		ro.product.device) echo "$device" ;;
		*) return 2 ;;
	esac
}
check_h50_archive() (
	H50_ONLY=1
	firmware=$1
	device=$2
	source <(sed -n '/^head_unit=/,/^coolray=0$/{ /^coolray=0$/!p; }' "$installer")
)
expect_failure check_h50_archive 05.05.10500 SX11QJ
expect_failure check_h50_archive 05.06.06000.H40.00032 SX11QJ
expect_failure check_h50_archive 05.07.06000.H50.00031 SX11QJ
expect_failure check_h50_archive 05.07.06000.H50.00032 SX11
check_h50_archive 05.07.06000.H50.00032 SX11QJ
[[ "$(grep -c 'STOP: this stick is only' "$log")" == 4 ]]

echo '11 Wi-Fi Direct installer scenarios passed'
