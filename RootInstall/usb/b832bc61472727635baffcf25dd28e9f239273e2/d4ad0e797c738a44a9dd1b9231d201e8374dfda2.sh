#!/system/bin/sh
# coorlay root installer: entry point.
# The head unit's /system/bin/update.sh (started at boot by the root service "klog") looks every 2 s for this
# exact folder/file name on a USB stick in /mnt/udisk1 or /mnt/udisk2 and runs it once per boot as root with
# "sh < file". The work is done by coorlay/install.sh on the same stick. It runs from a copy in internal memory,
# so pulling the stick out midway cannot cut the script off in the middle of a change.
for usb in /mnt/udisk1 /mnt/udisk2 /udisk/single; do
	if [ -f "$usb/coorlay/install.sh" ]; then
		copy=/data/local/tmp/coorlay-install.sh
		cat "$usb/coorlay/install.sh" > $copy && sh $copy "$usb" < /dev/null > /dev/null 2>&1
		rm $copy
		break
	fi
done
