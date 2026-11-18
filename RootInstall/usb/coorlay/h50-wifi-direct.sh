# Sourced by the root installer. The H50 supplicant emits tags that Android 4.3's
# WifiP2pDevice parser rejects. Keep the station MAC and the optional P2P device
# MAC in both station events. Drop the peer tags and the optional IP suffix.
H50_SUPPLICANT=/system/bin/wpa_supplicant
H50_BACKUP=$H50_SUPPLICANT.coorlay-h50-orig
H50_NEW=$H50_SUPPLICANT.coorlay-h50-new
h50_supplicant_changed=0

h50_hashes() {
	h50_busybox=$(command -v busybox) || return 1
	h50_hash=sha256sum
	h50_stock=32a8dc8fe7b877c1981b5616654ca9630d31676a1c9a8f5cb30dad34edc21536
	h50_compat=d1cb6861c122245240f3c1c85305c6ad8b88387046c66c7566a893c7a118b861
	if "$h50_busybox" "$h50_hash" /dev/null >/dev/null 2>&1; then return 0; fi
	h50_hash=sha1sum
	h50_stock=faa62dee38b4bbf06a8a1d3e849e9836e7058406
	h50_compat=ed4e2bf76995b2e380732792d59badbbb332f5e7
	"$h50_busybox" "$h50_hash" /dev/null >/dev/null 2>&1
}

h50_checksum() {
	[ -f "$1" ] && [ ! -L "$1" ] || return 1
	typeset hash_line
	hash_line=$("$h50_busybox" "$h50_hash" "$1") || return 1
	[ "${hash_line%% *}" = "$2" ]
}

h50_prepare_supplicant() {
	case "$(getprop ro.build.id)|$(getprop ro.product.device)" in
		05.07.06000.H50.00032\|SX11QJ) ;;
		*.H50.*) say "H50 Wi-Fi Direct: unsupported firmware for the supplicant fix"; return 1 ;;
		*) return 0 ;;
	esac
	case "$P2P_SERVICE_CMD" in
		"service p2p_supplicant $H50_SUPPLICANT "*) ;;
		*) say "H50 Wi-Fi Direct: init is not using the expected supplicant"; return 1 ;;
	esac
	if ! h50_hashes; then say "H50 Wi-Fi Direct: no working BusyBox checksum applet"; return 1; fi
	if h50_checksum "$H50_SUPPLICANT" "$h50_compat" && h50_checksum "$H50_BACKUP" "$h50_stock"; then
		say "H50 Wi-Fi Direct: compatible supplicant is already installed"
		return 0
	fi
	if ! h50_checksum "$H50_SUPPLICANT" "$h50_stock"; then
		say "H50 Wi-Fi Direct: unexpected supplicant; no patch applied"
		return 1
	fi
	if { [ -e "$H50_BACKUP" ] || [ -L "$H50_BACKUP" ]; } && ! h50_checksum "$H50_BACKUP" "$h50_stock"; then
		say "H50 Wi-Fi Direct: unexpected supplicant backup; no patch applied"
		return 1
	fi
	system_rw || return 1
	typeset status=1
	if h50_stage_supplicant && { [ -f "$H50_BACKUP" ] || ln "$H50_SUPPLICANT" "$H50_BACKUP"; } &&
		mv "$H50_NEW" "$H50_SUPPLICANT"; then
		h50_supplicant_changed=1
		status=0
		say "H50 Wi-Fi Direct: supplicant event fix installed; original saved"
	else
		say "H50 Wi-Fi Direct: could not install the supplicant event fix"
	fi
	rm -f "$H50_NEW"
	system_ro
	return "$status"
}

h50_stage_supplicant() {
	typeset offset context
	rm -f "$H50_NEW" && "$h50_busybox" cp -p "$H50_SUPPLICANT" "$H50_NEW" || return 1
	# In the verified ELF's .rodata, terminate the two station-address format
	# strings before their peer tags and AP-STA-CONNECTED before its second %s.
	for offset in 913637 913676 913729; do
		"$h50_busybox" dd if=/dev/zero of="$H50_NEW" bs=1 seek="$offset" count=1 conv=notrunc || return 1
	done
	h50_checksum "$H50_NEW" "$h50_compat" || return 1
	context=$(h50_file_context "$H50_SUPPLICANT")
	if [ -n "$context" ]; then
		if [ "$(h50_file_context "$H50_NEW")" != "$context" ]; then
			chcon "$context" "$H50_NEW" || "$h50_busybox" chcon "$context" "$H50_NEW" || return 1
		fi
	elif [ "$(getenforce 2>/dev/null)" = Enforcing ] || [ "$(cat /sys/fs/selinux/enforce 2>/dev/null)" = 1 ]; then
		say "H50 Wi-Fi Direct: cannot preserve the supplicant's SELinux label"
		return 1
	fi
}

h50_file_context() {
	typeset field
	for field in $(ls -Z "$1" 2>/dev/null); do
		case "$field" in *:*:*:*) echo "$field"; return 0 ;; esac
	done
}

h50_restore_supplicant() {
	[ -e "$H50_BACKUP" ] || [ -L "$H50_BACKUP" ] || return 0
	if ! h50_hashes || ! h50_checksum "$H50_BACKUP" "$h50_stock"; then
		say "H50 Wi-Fi Direct: cannot verify the original supplicant backup"
		return 1
	fi
	if ! h50_checksum "$H50_SUPPLICANT" "$h50_compat" && ! h50_checksum "$H50_SUPPLICANT" "$h50_stock"; then
		say "H50 Wi-Fi Direct: current supplicant changed; backup left in place"
		return 1
	fi
	system_rw || return 1
	typeset status=1
	if h50_checksum "$H50_SUPPLICANT" "$h50_stock"; then
		rm "$H50_BACKUP" && status=0
	elif mv "$H50_BACKUP" "$H50_SUPPLICANT"; then
		h50_supplicant_changed=1
		status=0
		say "H50 Wi-Fi Direct: original supplicant restored"
	fi
	system_ro
	return "$status"
}
