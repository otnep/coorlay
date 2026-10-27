#!/system/bin/sh
# coorlay root installer for the Geely Coolray head unit (SX11, Neusoft, Android 4.3, firmware 05.05-05.07).
#
# Started by ../b832bc61472727635baffcf25dd28e9f239273e2/d4ad0e797c738a44a9dd1b9231d201e8374dfda2.sh, which the
# firmware runs as root from a USB stick (see ROOT-INSTALL.md). Argument: the USB stick root.
# Uses only mksh builtins and toolbox: Android 4.3 has no grep and no printf outside busybox (busybox 1.15.3 is
# there, applets not linked), so text is matched with "while read" and printed with mksh "print".
#
# Safety rules (stock firmware, CommunityFull, any other head unit):
# - Nothing is changed unless the head unit is a Coolray Neusoft (manufacturer, model, board, Android 4.3, 05.05-05.07).
# - The head unit is never rebooted. /system is written only with ENABLE_WIFI_DIRECT_FEATURE=1 (off unless the
#   "wifi-direct-enable" stick sets it): one file, android.hardware.wifi.direct.xml, added only where the firmware
#   lacks it and has everything else Wi-Fi Direct needs; RESTORE_WIFI_DIRECT=1 removes only that file of ours.
# - Since v4 p2p_supplicant.conf stays as the firmware made it. The fix is one symbolic link,
#   /data/misc/wifi/sockets/p2p0 -> /data/system/wpa_supplicant/p2p0, made only when the firmware has exactly the
#   known bug (template and file carry a stock ctrl_interface into /data/system/wpa_supplicant: H30
#   "DIR=/data/system/wpa_supplicant GROUP=wifi", H50 "/data/system/wpa_supplicant").
#   Any other value (CommunityFull or another mod may set its own) is left alone.
# - v3 changed ctrl_interface to DIR=/data/misc/wifi/sockets instead. wpa_supplicant then chmods that directory to
#   0750 on every start, system_server can no longer create its wpa_ctrl_* client sockets there, and ALL of Wi-Fi
#   client mode broke ("Unable to open connection to supplicant ... Permission denied" for wlan0 and p2p0, the
#   supplicant restarting every 10 s; user's head unit, 2026-10-12). v4 puts v3's file back from its backup.
# - wpa_supplicant is stopped while files change (with update_config=1 it would write its old value back) and
#   Wi-Fi is turned on again only if it was on.
#
# Visible result: while it works the Android "About" screen is open; at the end coorlay opens on success,
# the "About" screen stays on failure. Everything is written to coorlay/logs/install-<date>.log on the stick.

VERSION=7
usb="$1"
dir="$usb/coorlay"
PKG=com.coorlay
ACTIVITY=$PKG/com.coorlay.coolray.CoolrayActivity
P2P_CONF=/data/misc/wifi/p2p_supplicant.conf
P2P_TEMPLATE=/system/etc/wifi/wpa_supplicant.conf
P2P_BACKUP=$P2P_CONF.coorlay-orig
P2P_ABSENT=$P2P_CONF.coorlay-absent
P2P_BROKEN="ctrl_interface=DIR=/data/system/wpa_supplicant GROUP=wifi"
# The same directory in the H50 firmware's template (05.07.06000.H50, issue #2). libhardware_legacy keeps a value
# beginning with "/" as it is, too, so the p2p0 socket lands in the same wrong place.
P2P_BROKEN_H50="ctrl_interface=/data/system/wpa_supplicant"
P2P_CTRL="ctrl_interface=DIR=/data/misc/wifi/sockets GROUP=wifi"
SOCKETS_DIR=/data/misc/wifi/sockets
P2P_SOCKET=$SOCKETS_DIR/p2p0
# Android 4.3 scans with the Wi-Fi client every this many ms while a Wi-Fi Direct group is up (default 60000). Each
# scan took the radio off the CarPlay channel for about 2 s: wlan0 link change and a picture/music stall every
# 60 s in coorlay's Wi-Fi Direct sessions (2026-10-12). v5 sets one hour; RESTORE puts the default 60000 back (4.3 "settings" has no delete).
P2P_SCAN_SETTING=wifi_scan_interval_p2p_connected_ms
P2P_SCAN_INTERVAL=3600000
# Where wpa_supplicant really opens the p2p0 control socket with the stock file.
P2P_REAL=/data/system/wpa_supplicant/p2p0
# Read by coorlay (no root): a new value makes it check Wi-Fi Direct silently and pick it as the default when it works.
P2P_MARKER=/data/local/tmp/coorlay-wifi-direct
FEATURE_XML=/system/etc/permissions/android.hardware.wifi.direct.xml
# Written into the feature file coorlay adds; RESTORE removes the file only when it carries this text.
FEATURE_TAG="added by the coorlay root installer"
ENTRY_DIR=b832bc61472727635baffcf25dd28e9f239273e2

INSTALL_APP=1
FIX_WIFI_DIRECT=1
RESTORE_WIFI_DIRECT=0
# v7: the H50 firmware 05.07.06000 has no android.hardware.wifi.direct.xml, so Android never starts Wi-Fi Direct and
# the link alone cannot help. Opt-in, only on the separate "wifi-direct-enable" stick.
ENABLE_WIFI_DIRECT_FEATURE=0
DUMP_SYSTEM_APPS=0
RUN_ONCE=1
# v6: copies of the stock phone and key-handling apps (and the framework they were optimized against) go to
# coorlay/dump on the stick. Only read: the patched popview/btphoneApp are built from exactly these files.
DUMP_APPS="KC2_popview KC2_btphoneApp KC2_btphone"
# Testing only (emulator): ANY_HEAD_UNIT=1 in install.conf skips the Coolray identity check (not the bug checks).
ANY_HEAD_UNIT=0

[ -d "$dir" ] || exit 1
mkdir -p "$dir/logs"
exec > "$dir/logs/install-$(date +%Y%m%d-%H%M%S).log" 2>&1

say() { echo "$(date +%H:%M:%S) $*"; }
failed=0
cr=$(print -n '\r')

# install.conf is only read, never executed: known names with the value 0 or 1; Windows line endings are fine.
if [ -f "$dir/install.conf" ]; then
	while IFS= read -r line || [ -n "$line" ]; do
		line="${line%$cr}"
		case "$line" in ''|'#'*) continue ;; esac
		name="${line%%=*}"; value="${line#*=}"
		case "$name=$value" in
			INSTALL_APP=[01]) INSTALL_APP=$value ;;
			FIX_WIFI_DIRECT=[01]) FIX_WIFI_DIRECT=$value ;;
			RESTORE_WIFI_DIRECT=[01]) RESTORE_WIFI_DIRECT=$value ;;
			ENABLE_WIFI_DIRECT_FEATURE=[01]) ENABLE_WIFI_DIRECT_FEATURE=$value ;;
			DUMP_SYSTEM_APPS=[01]) DUMP_SYSTEM_APPS=$value ;;
			RUN_ONCE=[01]) RUN_ONCE=$value ;;
			ANY_HEAD_UNIT=[01]) ANY_HEAD_UNIT=$value ;;
			*) say "install.conf: ignored line: $line" ;;
		esac
	done < "$dir/install.conf"
fi
if [ "$RESTORE_WIFI_DIRECT" = 1 ]; then FIX_WIFI_DIRECT=0; ENABLE_WIFI_DIRECT_FEATURE=0; fi

say "coorlay root installer v$VERSION from $usb"
say "user: $(id)"
say "head unit: manufacturer=$(getprop ro.product.manufacturer) model=$(getprop ro.product.model)" \
	"board=$(getprop ro.product.board) android=$(getprop ro.build.version.release) firmware=$(getprop ro.build.id)" \
	"device=$(getprop ro.product.device)"
say "settings: INSTALL_APP=$INSTALL_APP FIX_WIFI_DIRECT=$FIX_WIFI_DIRECT RESTORE_WIFI_DIRECT=$RESTORE_WIFI_DIRECT" \
	"ENABLE_WIFI_DIRECT_FEATURE=$ENABLE_WIFI_DIRECT_FEATURE DUMP_SYSTEM_APPS=$DUMP_SYSTEM_APPS RUN_ONCE=$RUN_ONCE ANY_HEAD_UNIT=$ANY_HEAD_UNIT"

coolray=0
case "$(getprop ro.product.manufacturer)|$(getprop ro.product.model)|$(getprop ro.product.board)|$(getprop ro.build.version.release)|$(getprop ro.build.id)" in
	Neusoft\|GEELY\|C3ALFUS\|4.3\|05.05.*|Neusoft\|GEELY\|C3ALFUS\|4.3\|05.06.*|Neusoft\|GEELY\|C3ALFUS\|4.3\|05.07.*) coolray=1 ;;
esac
if [ $coolray = 0 ] && [ "$ANY_HEAD_UNIT" = 1 ]; then say "TEST: not a Coolray, continuing because ANY_HEAD_UNIT=1"; coolray=1; fi
if [ $coolray = 0 ]; then
	say "STOP: this is not a supported Coolray head unit; nothing was changed"
	if [ "$RUN_ONCE" = 1 ] && [ -d "$usb/$ENTRY_DIR" ]; then mv "$usb/$ENTRY_DIR" "$usb/_$ENTRY_DIR"; fi
	sync
	exit 1
fi

# update.sh may find the stick seconds after power-on, before the package manager is up.
n=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $n -lt 120 ]; do sleep 2; n=$((n + 1)); done
say "boot completed=$(getprop sys.boot_completed) after $((n * 2)) s of waiting"
sleep 5
am start -a android.settings.DEVICE_INFO_SETTINGS > /dev/null 2>&1

# Text helpers without grep.
# Prints the ctrl_interface lines of a file.
ctrl_lines() { while IFS= read -r l || [ -n "$l" ]; do case "$l" in ctrl_interface=*) print -r -- "${l%$cr}" ;; esac; done < "$1"; }
# Number of network={ blocks (saved P2P groups); their contents hold passwords and are never logged.
network_count() { c=0; while IFS= read -r l || [ -n "$l" ]; do case "$l" in network=*) c=$((c + 1)) ;; esac; done < "$1"; echo $c; }
# The file's own ctrl_interface value when it has exactly one such line, "none" or "several" otherwise.
ctrl_value() {
	lines=$(ctrl_lines "$1")
	if [ -z "$lines" ]; then echo none; return; fi
	case "$lines" in *"
"*) echo several ;; *) print -r -- "$lines" ;; esac
}
owner_group() { set -- $(ls -l "$1"); print -r -- "$2:$3"; }
size_of() { set -- $(ls -l "$1"); print -r -- "$4"; }
# The stock ctrl_interface values with the p2p0 socket-path bug (H30 and H50 firmware).
stock_ctrl() { case "$1" in "$P2P_BROKEN"|"$P2P_BROKEN_H50") return 0 ;; esac; return 1; }
# Init scripts of the firmware (the ramdisk is readable by root).
rc_files() { for rc in /init.rc /init.*.rc; do [ -f "$rc" ] && print -r -- "$rc"; done; }

# The init service Android starts for Wi-Fi Direct: sets P2P_SERVICE_RC and P2P_SERVICE_CMD (continuation lines
# joined), fails when no /init*.rc declares "service p2p_supplicant".
p2p_service() {
	for rc in $(rc_files); do
		cmd=; cont=0
		while IFS= read -r l || [ -n "$l" ]; do
			l="${l%$cr}"
			if [ $cont = 1 ]; then cmd="$cmd ${l#"${l%%[! 	]*}"}"
			else case "$l" in "service p2p_supplicant "*) cmd="$l" ;; *) continue ;; esac
			fi
			case "$cmd" in
				*\\) cmd="${cmd%\\}"; cont=1 ;;
				*) P2P_SERVICE_RC=$rc; P2P_SERVICE_CMD=$cmd; return 0 ;;
			esac
		done < "$rc"
	done
	return 1
}

# 1 when a supplicant binary carries Wi-Fi Direct (P2P) code, 0 when it does not, nothing when this cannot be told:
# the 4.3 toolbox has no grep, busybox may lack it.
supplicant_p2p() {
	[ -x /system/bin/busybox ] || return 0
	n=$(/system/bin/busybox grep -c P2P-GROUP-STARTED "$1" 2>/dev/null)
	case "$n" in ''|*[!0-9]*) ;; 0) echo 0 ;; *) echo 1 ;; esac
}

# The feature file is ours when it carries FEATURE_TAG.
feature_ours() {
	[ -f $FEATURE_XML ] || return 1
	while IFS= read -r l || [ -n "$l" ]; do case "$l" in *"$FEATURE_TAG"*) return 0 ;; esac; done < $FEATURE_XML
	return 1
}

# Read only, on every run: what Wi-Fi Direct needs from the firmware and the hardware. Written for the H50 firmware,
# whose Wi-Fi setup differs from the H30 one coorlay was built on (issue #2). Wi-Fi passwords are never printed.
wifi_hardware_info() {
	say "--- Wi-Fi hardware and firmware (read only)"
	say "build: display=$(getprop ro.build.display.id) incremental=$(getprop ro.build.version.incremental)" \
		"hardware=$(getprop ro.hardware) platform=$(getprop ro.board.platform)"
	say "permissions:" $(ls /system/etc/permissions 2>&1)
	say "init scripts:" $(rc_files)
	for rc in $(rc_files); do
		while IFS= read -r l || [ -n "$l" ]; do
			case "$l" in *p2p*|*supplicant*|*suppl_con*|*hostapd*) print -r -- "$rc: ${l%$cr}" ;; esac
		done < "$rc"
	done
	if p2p_service; then say "p2p_supplicant service ($P2P_SERVICE_RC): $P2P_SERVICE_CMD"; else say "p2p_supplicant service: none in /init*.rc"; fi
	for b in /system/bin/*supplicant* /system/bin/hostapd*; do
		[ -f "$b" ] || continue
		case "$b" in
			*supplicant*) p2p=$(supplicant_p2p "$b"); say "binary: $(ls -l "$b" 2>&1); P2P code: ${p2p:-cannot tell}" ;;
			*) say "binary: $(ls -l "$b" 2>&1)" ;;
		esac
	done
	say "ls /system/etc/wifi:"; ls -l /system/etc/wifi 2>&1
	say "net interfaces:" $(ls /sys/class/net 2>&1)
	say "wlan0 driver: $(ls -l /sys/class/net/wlan0/device/driver 2>&1)"
	say "wlan0 device:" $(cat /sys/class/net/wlan0/device/uevent 2>&1)
	say "kernel modules:" $(while read -r m rest; do print -r -- "$m"; done < /proc/modules)
	say "/system/lib/modules:" $(ls /system/lib/modules 2>&1)
	say "Wi-Fi properties:"
	getprop | while IFS= read -r l; do case "$l" in *wifi*|*wlan*|*p2p*|*supplicant*|*suppl_con*) print -r -- "$l" ;; esac; done
	say "/system mount: $(system_mount_opts)"
}

# Mount options of /system, e.g. "ro,relatime".
system_mount_opts() {
	while IFS= read -r l; do set -- $l; [ "$2" = /system ] && { print -r -- "$4"; return 0; }; done < /proc/mounts
}
# /system writable for one change and read-only again afterwards when it was read-only.
system_was_ro=0
system_rw() {
	system_was_ro=0
	case ",$(system_mount_opts)," in *,ro,*) system_was_ro=1 ;; *) return 0 ;; esac
	if mount -o remount,rw /system; then return 0; fi
	say "/system could not be made writable"; system_was_ro=0; return 1
}
system_ro() {
	sync
	[ $system_was_ro = 1 ] || return 0
	mount -o remount,ro /system || say "/system could not be made read-only again (it is after the next start)"
	system_was_ro=0
}

package_version() {
	dumpsys package $PKG | while IFS= read -r v; do case "$v" in *versionName=*) print -r -- "${v#"${v%%[! ]*}"}"; break ;; esac; done
}

install_app() {
	set -- "$dir"/*.apk
	if [ ! -f "$1" ]; then say "APK: no *.apk in $dir"; return 1; fi
	if [ $# -gt 1 ]; then say "APK: $# files in $dir ($*); keep exactly one coorlay APK there"; return 1; fi
	apk="$1"
	say "APK: $apk"
	say "before: $(package_version)"
	tmp=/data/local/tmp/coorlay-install.apk
	if ! cat "$apk" > $tmp; then say "APK: copy to $tmp failed"; rm $tmp; return 1; fi
	chmod 644 $tmp
	result=$(pm install -r $tmp 2>&1)
	rm $tmp
	say "pm install -r: $result"
	case "$result" in
		*Success*) ;;
		*INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*INSTALL_FAILED_INCONSISTENT_CERTIFICATES*)
			say "The installed coorlay is signed with another key. Uninstall it in Android settings first (its settings are lost) and run again."
			return 1 ;;
		*INSTALL_FAILED_VERSION_DOWNGRADE*)
			say "The installed coorlay is newer than this APK. Put a newer APK on the stick."
			return 1 ;;
		*) return 1 ;;
	esac
	say "after: $(package_version)"
	return 0
}

wifi_direct_state() {
	say "--- Wi-Fi Direct state: $1"
	say "feature: $(ls -l $FEATURE_XML 2>&1)"
	say "wifi_on=$(settings get global wifi_on 2>/dev/null) init.svc.p2p_supplicant=$(getprop init.svc.p2p_supplicant)" \
		"init.svc.wpa_supplicant=$(getprop init.svc.wpa_supplicant) init.svc.rtw_suppl_con=$(getprop init.svc.rtw_suppl_con)" \
		"wifi.interface=$(getprop wifi.interface)"
	say "template $P2P_TEMPLATE: $(ctrl_value $P2P_TEMPLATE 2>&1)"
	for path in /data/misc/wifi $SOCKETS_DIR /data/system/wpa_supplicant; do say "ls $path:"; ls -l $path 2>&1; done
	say "ls -ld: $(ls -ld $SOCKETS_DIR 2>&1); $(ls -ld /data/system/wpa_supplicant 2>&1)"
	if [ -f $P2P_CONF ]; then
		say "$P2P_CONF: $(ls -l $P2P_CONF); ctrl_interface: $(ctrl_value $P2P_CONF); saved groups: $(network_count $P2P_CONF)"
	else
		say "$P2P_CONF: absent"
	fi
	if [ -f $P2P_MARKER ]; then say "marker: $(cat $P2P_MARKER)"; else say "marker: none"; fi
	say "$P2P_SCAN_SETTING=$(settings get global $P2P_SCAN_SETTING 2>&1)"
}

# Wi-Fi (and wpa_supplicant with it) is stopped while the file changes and started again only if it ran.
supplicant_running() {
	for s in p2p_supplicant wpa_supplicant rtw_suppl_con; do [ "$(getprop init.svc.$s)" = running ] && return 0; done
	return 1
}
wifi_was_on=0
stop_wifi() {
	supplicant_running || return 0
	wifi_was_on=1
	say "Wi-Fi Direct: stopping Wi-Fi while the file changes"
	svc wifi disable
	n=0
	while supplicant_running && [ $n -lt 20 ]; do sleep 1; n=$((n + 1)); done
	if supplicant_running; then say "Wi-Fi Direct: wpa_supplicant still runs after 20 s"; return 1; fi
	return 0
}
start_wifi() {
	[ $wifi_was_on = 1 ] || return 0
	say "Wi-Fi Direct: turning Wi-Fi on again"
	svc wifi enable
}

# Android 4.3 creates p2p_supplicant.conf from the template, whose "ctrl_interface=DIR=/data/system/wpa_supplicant"
# it does not rewrite. wpa_supplicant then opens the p2p0 control socket there, while WifiHW connects to
# /data/misc/wifi/sockets/p2p0: "P2p socket connection failed". A link at the expected place leads to the real one.
fix_wifi_direct() {
	if [ ! -f $FEATURE_XML ]; then
		say "Wi-Fi Direct: $FEATURE_XML is missing: this firmware does not declare Wi-Fi Direct and Android does not start it." \
			"The coorlay-usb-wifi-direct-enable stick can add the file. Not changed"
		return 1
	fi
	template=$(ctrl_value $P2P_TEMPLATE 2>/dev/null)
	if ! stock_ctrl "$template"; then
		say "Wi-Fi Direct: template ctrl_interface is '$template', not the known stock bug; not changed"; return 1
	fi
	undo=0
	if [ -f $P2P_CONF ]; then
		current=$(ctrl_value $P2P_CONF)
		if [ "$current" = "$P2P_CTRL" ]; then
			say "Wi-Fi Direct: p2p_supplicant.conf carries the v3 change that broke Wi-Fi; it is put back"; undo=1
		elif [ "$current" != "$template" ]; then
			say "Wi-Fi Direct: p2p_supplicant.conf has ctrl_interface '$current' (changed by someone else); not changed"; return 1
		fi
	fi
	stop_wifi || { start_wifi; return 1; }
	result=0
	if [ $undo = 1 ]; then restore_conf || result=1; fi
	if [ $result = 0 ]; then repair_sockets_dir; make_link || result=1; fi
	start_wifi
	[ $result = 0 ] || return 1
	set_p2p_scan_interval
	await_socket
	mark_wifi_direct
	return 0
}

# v3's ctrl_interface made wpa_supplicant chmod the directory to 0750; Android's init.rc makes it 0770 wifi:wifi.
repair_sockets_dir() {
	chown wifi:wifi $SOCKETS_DIR
	chmod 770 $SOCKETS_DIR
	say "Wi-Fi Direct: $(ls -ld $SOCKETS_DIR 2>&1)"
}

make_link() {
	# No readlink in the 4.3 toolbox: "ls -l" prints "... p2p0 -> target" for a link.
	if [ -L $P2P_SOCKET ] && case "$(ls -l $P2P_SOCKET 2>/dev/null)" in *"-> $P2P_REAL") true ;; *) false ;; esac; then
		say "Wi-Fi Direct: link already there: $(ls -l $P2P_SOCKET 2>&1)"; return 0
	fi
	# A socket file left by v3's supplicant (stopped now) or an older link.
	if [ -e $P2P_SOCKET ] || [ -L $P2P_SOCKET ]; then say "Wi-Fi Direct: removing old $(ls -l $P2P_SOCKET 2>&1)"; rm $P2P_SOCKET; fi
	if ! ln -s $P2P_REAL $P2P_SOCKET; then say "Wi-Fi Direct: ln -s failed; not changed"; return 1; fi
	if [ ! -L $P2P_SOCKET ]; then say "Wi-Fi Direct: the link did not appear; not changed"; return 1; fi
	sync
	say "Wi-Fi Direct: link made: $(ls -l $P2P_SOCKET 2>&1)"
	return 0
}

# Puts back the file v3 changed (from its backup, or removes it when it did not exist before).
restore_conf() {
	if [ -f $P2P_BACKUP ]; then
		ownership=$(owner_group $P2P_BACKUP)
		new=$P2P_CONF.coorlay-new
		if cat $P2P_BACKUP > $new && chown $ownership $new && chmod 660 $new && mv $new $P2P_CONF; then
			rm $P2P_BACKUP
			say "Wi-Fi Direct: original p2p_supplicant.conf restored: $(ctrl_value $P2P_CONF)"
		else
			rm $new 2>/dev/null; say "Wi-Fi Direct: restoring p2p_supplicant.conf failed"; return 1
		fi
	elif [ -f $P2P_ABSENT ]; then
		rm $P2P_CONF $P2P_ABSENT
		say "Wi-Fi Direct: p2p_supplicant.conf removed (it did not exist before); Android creates it again"
	else
		say "Wi-Fi Direct: no backup of the original p2p_supplicant.conf; not restored"; return 1
	fi
	sync
	return 0
}

# Informational: the socket appears only when Wi-Fi client mode runs (the head-unit hotspot keeps it off).
await_socket() {
	if [ $feature_added = 1 ]; then say "Wi-Fi Direct: it starts after the next start of the head unit (feature file added now)"; return; fi
	if [ $wifi_was_on = 0 ]; then say "Wi-Fi Direct: Wi-Fi is off now; the link works on the next Wi-Fi start"; return; fi
	n=0
	while [ ! -e $P2P_REAL ] && [ $n -lt 30 ]; do sleep 1; n=$((n + 1)); done
	if [ -e $P2P_REAL ]; then say "Wi-Fi Direct: OK, $P2P_REAL appeared after ${n} s"
	else say "Wi-Fi Direct: $P2P_REAL did not appear within 30 s (coorlay checks Wi-Fi Direct itself)"; fi
	sleep 10
	say "Wi-Fi Direct: Android's view (last WifiHW/WifiP2pService/WifiStateMachine errors):"
	logcat -v time -t 12 -s WifiHW:E WifiP2pService:E WifiStateMachine:E 2>&1
}

# Only the global setting is written (no file in /system); Android reads it when a Wi-Fi Direct group connects.
set_p2p_scan_interval() {
	if settings put global $P2P_SCAN_SETTING $P2P_SCAN_INTERVAL; then
		say "Wi-Fi Direct: $P2P_SCAN_SETTING=$(settings get global $P2P_SCAN_SETTING 2>&1) (Wi-Fi client scans during Wi-Fi Direct)"
	else
		say "Wi-Fi Direct: could not set $P2P_SCAN_SETTING (scans every 60 s stay)"
	fi
}

mark_wifi_direct() {
	if [ -e $P2P_REAL ]; then socket=ok; else socket=pending; fi
	if feature_ours; then feature=coorlay; else feature=firmware; fi
	print -r -- "fixed=$(date +%Y%m%d-%H%M%S) installer=$VERSION method=link socket=$socket feature=$feature" > $P2P_MARKER
	chmod 644 $P2P_MARKER
	say "Wi-Fi Direct: marker for coorlay: $(cat $P2P_MARKER)"
}

# Android 4.3 starts Wi-Fi Direct (WifiP2pService and the p2p_supplicant service instead of wpa_supplicant) only on a
# firmware that declares the feature; the package manager reads it once per start. The H50 firmware 05.07.06000 has no
# such file (issue #2). Added only when the firmware has the rest: a p2p_supplicant service on p2p0, its binary
# (with P2P code, when busybox can tell) and the stock template the link fix knows. If the driver still cannot do
# P2P, Wi-Fi client mode may not start: RESTORE_WIFI_DIRECT=1 removes the file again.
feature_added=0
enable_wifi_direct_feature() {
	if [ -f $FEATURE_XML ]; then say "Wi-Fi Direct feature: already declared: $(ls -l $FEATURE_XML 2>&1); nothing added"; return 0; fi
	if ! p2p_service; then say "Wi-Fi Direct feature: no p2p_supplicant service in /init*.rc; not added"; return 1; fi
	case "$P2P_SERVICE_CMD" in
		*-ip2p0*) ;;
		*) say "Wi-Fi Direct feature: the p2p_supplicant service does not run on p2p0 ($P2P_SERVICE_CMD); not added"; return 1 ;;
	esac
	set -- ${P2P_SERVICE_CMD#service p2p_supplicant }
	if [ ! -f "$1" ]; then say "Wi-Fi Direct feature: $1 of the p2p_supplicant service is missing; not added"; return 1; fi
	case "$(supplicant_p2p "$1")" in
		0) say "Wi-Fi Direct feature: $1 has no Wi-Fi Direct code; not added"; return 1 ;;
		1) say "Wi-Fi Direct feature: $1 has Wi-Fi Direct code" ;;
		*) say "Wi-Fi Direct feature: cannot check $1 for Wi-Fi Direct code (no busybox grep); going on" ;;
	esac
	template=$(ctrl_value $P2P_TEMPLATE 2>/dev/null)
	if ! stock_ctrl "$template"; then
		say "Wi-Fi Direct feature: template ctrl_interface is '$template', which the link fix does not know; not added"; return 1
	fi
	system_rw || { say "Wi-Fi Direct feature: not added"; return 1; }
	new=$FEATURE_XML.coorlay-new
	{
		print -r -- '<?xml version="1.0" encoding="utf-8"?>'
		print -r -- "<!-- $FEATURE_TAG v$VERSION on $(date +%Y%m%d-%H%M%S). The standard Android file, as in the"
		print -r -- "     Coolray 05.05.10500 firmware. RESTORE_WIFI_DIRECT=1 on a coorlay USB stick removes it. -->"
		print -r -- '<permissions>'
		print -r -- '    <feature name="android.hardware.wifi.direct" />'
		print -r -- '</permissions>'
	} > $new
	if chown root:root $new && chmod 644 $new && mv $new $FEATURE_XML && feature_ours; then
		feature_added=1
		say "Wi-Fi Direct feature: added $(ls -l $FEATURE_XML 2>&1)"
	else
		rm $new 2>/dev/null
		say "Wi-Fi Direct feature: writing $FEATURE_XML failed; not added"
	fi
	system_ro
	[ $feature_added = 1 ]
}

# Only the file coorlay added; the firmware's own (or another mod's) stays.
remove_wifi_direct_feature() {
	if [ ! -f $FEATURE_XML ]; then say "Wi-Fi Direct feature: not declared; nothing to remove"; return 0; fi
	if ! feature_ours; then say "Wi-Fi Direct feature: $FEATURE_XML is not coorlay's (firmware or another mod); left alone"; return 0; fi
	system_rw || { say "Wi-Fi Direct feature: not removed"; return 1; }
	result=0
	if rm $FEATURE_XML; then say "Wi-Fi Direct feature: coorlay's $FEATURE_XML removed"; feature_removed=1
	else say "Wi-Fi Direct feature: removing $FEATURE_XML failed"; result=1
	fi
	system_ro
	return $result
}
feature_removed=0

# Only coorlay's own changes are undone: the link, and v3's file if it is still there.
restore_wifi_direct() {
	rm $P2P_MARKER 2>/dev/null
	stop_wifi || { start_wifi; return 1; }
	result=0
	if [ -L $P2P_SOCKET ]; then rm $P2P_SOCKET && say "Wi-Fi Direct: link removed"; else say "Wi-Fi Direct: no link"; fi
	if [ -f $P2P_CONF ] && [ "$(ctrl_value $P2P_CONF)" = "$P2P_CTRL" ]; then restore_conf || result=1; fi
	repair_sockets_dir
	settings put global $P2P_SCAN_SETTING 60000 && say "Wi-Fi Direct: $P2P_SCAN_SETTING back to the firmware default 60000"
	sync
	start_wifi
	return $result
}

# No cp in the 4.3 toolbox: files are copied with cat. The listing keeps sizes and dates, which cat does not.
copy_file() {
	if cat "$1" > "$2/${1##*/}"; then say "dump: $1 ($(size_of "$1") bytes)"; return 0; fi
	say "dump: copying $1 failed"; return 1
}

# Read only: nothing on the head unit changes. The odex files can only be read back (deodexed) against the
# framework of this very firmware, so /system/framework goes along (about 40 MB).
dump_system_apps() {
	out="$dir/dump/$(getprop ro.build.id)-$(date +%Y%m%d-%H%M%S)"
	if ! mkdir -p "$out/app" "$out/framework"; then say "dump: cannot create $out"; return 1; fi
	result=0
	{
		print -r -- "firmware=$(getprop ro.build.id) display=$(getprop ro.build.display.id) incremental=$(getprop ro.build.version.incremental)"
		print -r -- "device=$(getprop ro.product.device) date=$(getprop ro.build.date)"
		print -r -- "BOOTCLASSPATH=$BOOTCLASSPATH"
		for key in btAudioState geely_modesource carplay_call_timestamp bt_call_timestamp; do
			print -r -- "settings system $key=$(settings get system $key 2>&1)"
		done
	} > "$out/info.txt"
	ls -l /system/app > "$out/system-app.txt" 2>&1
	ls -l /system/framework > "$out/system-framework.txt" 2>&1
	ls -l /data/dalvik-cache > "$out/dalvik-cache.txt" 2>&1
	# The package manager's records of these apps (code path, timestamps); the rest of packages.xml stays private.
	while IFS= read -r l || [ -n "$l" ]; do
		case "$l" in *'<package '*KC2_popview*|*'<package '*KC2_btphone*|*'<updated-package '*KC2_*) print -r -- "$l" ;; esac
	done < /data/system/packages.xml > "$out/packages.txt" 2>&1
	for name in $DUMP_APPS; do
		found=0
		for f in /system/app/$name.apk /system/app/$name.odex; do
			[ -f $f ] || continue
			found=1
			copy_file $f "$out/app" || result=1
		done
		# A deodexed (modded) app is optimized into the dalvik cache instead of a .odex next to it.
		for f in /data/dalvik-cache/system@app@$name.apk@classes.dex; do
			[ -f $f ] && { copy_file $f "$out/app" || result=1; }
		done
		[ $found = 1 ] || { say "dump: /system/app/$name.apk not found"; result=1; }
		dumpsys package "$(package_of $name)" > "$out/app/$name-dumpsys.txt" 2>&1
	done
	copied=0
	for f in /system/framework/*; do
		[ -f "$f" ] || continue
		if copy_file "$f" "$out/framework" > /dev/null; then copied=$((copied + 1)); else say "dump: copying $f failed"; result=1; fi
	done
	say "dump: framework copied ($copied files)"
	sync
	say "dump: done in $out"
	return $result
}

# Package names of the dumped apps (dumpsys needs the name, not the file).
package_of() {
	case "$1" in
		KC2_popview) print -r -- com.neusoft.optimus.wheeljack.popview ;;
		KC2_btphoneApp) print -r -- bt.ecarxtestn.com.btphoneapp ;;
		KC2_btphone) print -r -- com.neusoft.geely.btphone ;;
		*) print -r -- "$1" ;;
	esac
}

wifi_hardware_info

if [ "$DUMP_SYSTEM_APPS" = 1 ]; then dump_system_apps || failed=1; fi

if [ "$FIX_WIFI_DIRECT" = 1 ] || [ "$RESTORE_WIFI_DIRECT" = 1 ] || [ "$ENABLE_WIFI_DIRECT_FEATURE" = 1 ]; then
	wifi_direct_state before
	if [ "$RESTORE_WIFI_DIRECT" = 1 ]; then
		restore_wifi_direct || failed=1
		remove_wifi_direct_feature || failed=1
	else
		if [ "$ENABLE_WIFI_DIRECT_FEATURE" = 1 ]; then enable_wifi_direct_feature || failed=1; fi
		if [ "$FIX_WIFI_DIRECT" = 1 ]; then fix_wifi_direct || failed=1; fi
	fi
	wifi_direct_state after
fi

if [ "$INSTALL_APP" = 1 ]; then install_app || failed=1; fi

if [ "$RUN_ONCE" = 1 ] && [ -d "$usb/$ENTRY_DIR" ]; then
	rm -rf "$usb/_$ENTRY_DIR"
	mv "$usb/$ENTRY_DIR" "$usb/_$ENTRY_DIR" && say "entry folder renamed to _$ENTRY_DIR (rename it back to run again)"
fi
sync

if [ $feature_added = 1 ] || [ $feature_removed = 1 ]; then
	say "RESTART NEEDED: Android reads the Wi-Fi Direct feature only when it starts. Turn the ignition off, wait until" \
		"the screen goes dark, and turn it on again"
fi
if [ $failed = 0 ]; then
	say "DONE: everything succeeded"
	am force-stop com.android.settings
	# Also takes a fresh install out of the "stopped" state, so coorlay gets BOOT_COMPLETED from now on.
	am start -n $ACTIVITY > /dev/null 2>&1
else
	say "DONE WITH ERRORS: see the lines above"
fi
sync
