#!/system/bin/sh
# LG SimpLink bridge: answers LG's vendor handshake (as libcec's CSLCommandHandler does)
# and feeds the TV remote's keys into a virtual uinput remote.
PA=3.0.0.0
DIR=/data/local/tmp
FIFO=$DIR/slb.fifo
LOG=$DIR/slb.log
cec() { cec-ctl -d0 "$@" >/dev/null 2>&1; }
activate() { cec --to 0 --image-view-on; cec --to 15 --active-source phys-addr=$PA; cec --to 0 --menu-status menu-state=activated; }

# CEC UI command -> Linux key code
lk() {
  case "$1" in
    0x00) k=353;; 0x01) k=103;; 0x02) k=108;; 0x03) k=105;; 0x04) k=106;;
    0x09) k=172;; 0x0a) k=141;; 0x0b) k=139;; 0x0d) k=158;;
    0x20) k=11;; 0x21) k=2;; 0x22) k=3;; 0x23) k=4;; 0x24) k=5;;
    0x25) k=6;; 0x26) k=7;; 0x27) k=8;; 0x28) k=9;; 0x29) k=10;;
    0x30) k=402;; 0x31) k=403;; 0x35) k=358;;
    0x44) k=207;; 0x45) k=172;; 0x46) k=164;; 0x48) k=168;; 0x49) k=208;;
    0x4b) k=163;; 0x4c) k=165;; 0x61) k=164;;
    0x71) k=401;; 0x72) k=398;; 0x73) k=399;; 0x74) k=400;;
    *) k=;;
  esac
}
KEYS="143,353,103,108,105,106,172,141,139,158,2,3,4,5,6,7,8,9,10,11,402,403,358,207,166,119,168,208,163,165,164,398,399,400,401"
# uinput only runs a command once the next one starts arriving, so follow each with a no-op
flush() { echo "{\"id\":1,\"command\":\"delay\",\"duration\":1}" >&3; }
# every TV key press becomes one complete ~120ms press: the TV releases after ~70ms, and
# presses that short get ignored after a few idle seconds. Holding still repeats, since
# the TV resends the press about every 250ms.
HDMI=/sys/class/drm/card0-HDMI-A-1/dpms
REQ=/data/data/com.sall.pihome/files/request
INSTLOG=/data/data/com.sall.pihome/files/install.log
press() { lk "$1"; [ -z "$k" ] && return
  # screen off (Veille button or sleep timeout): this press only wakes the Pi
  read -r screen < $HDMI 2>/dev/null
  [ "$screen" = Off ] && k=143
  echo "{\"id\":1,\"command\":\"inject\",\"events\":[1,$k,1,0,0,0]}" >&3; flush
  # uinput's own "delay" command doesn't hold back the next event, so wait for real
  (sleep 0.12; echo "{\"id\":1,\"command\":\"inject\",\"events\":[1,$k,0,0,0,0]}" >&3; flush) & }

# drop a virtual remote left over from a previous run
for p in $(pgrep -f com.android.commands.uinput); do kill $p; done
rm -f $FIFO; mkfifo $FIFO
uinput $FIFO >/dev/null 2>&1 &
exec 3>$FIFO
echo "{\"id\":1,\"command\":\"register\",\"name\":\"LG SimpLink Remote\",\"vid\":4660,\"pid\":22136,\"bus\":\"usb\",\"configuration\":[{\"type\":\"UI_SET_EVBIT\",\"data\":[1]},{\"type\":\"UI_SET_KEYBIT\",\"data\":[$KEYS]}]}" >&3

# Keep an existing LG-recorder registration: clearing and claiming it again makes the TV drop
# the Pi, and it stops forwarding the remote until its input is switched away and back.
if cec-ctl -d0 2>/dev/null | grep -q "Logical Address *: 1 (Recording" &&
   cec-ctl -d0 2>/dev/null | grep -q "Vendor ID *: 0x00e091"; then
  cec --to 15 --report-physical-addr phys-addr=$PA,prim-devtype=record
else
  cec --clear
  cec --record --vendor-id 0x00e091 --osd-name "Raspberry Pi" --cec-version-1.4
  cec --to 15 --device-vendor-id vendor-id=0x00e091
  activate
fi

handle() { # $1=opcode line  $2=first parameter value
  case "$1" in
    *USER_CONTROL_PRESSED*) c=${2##*\(}; press "${c%\)*}";;
    *VENDOR_COMMAND\ \(0x89\)*)
      case "$2" in
        "0x01")   cec --to 0 --vendor-command payload=0x02:0x05;;
        "0x03 "*) cec --to 0 --report-power-status pwr-state=to-on
                  cec --to 15 --report-physical-addr phys-addr=$PA,prim-devtype=record
                  (sleep 3; activate) &;;
        "0x04 "*) cec --to 0 --vendor-command payload=0x05:0x01
                  cec --to 0 --deck-status deck-info=0x20
                  cec --to 0 --report-power-status pwr-state=on;;
        "0x0b"|"0xa0") cec --to 0 --report-power-status pwr-state=to-on;;
      esac;;
    *GIVE_DECK_STATUS*)
      cec --to 0 --deck-status deck-info=0x20
      case "$2" in *0x01*) activate;; esac;;
    *SET_STREAM_PATH*) activate;;
  esac
}

: > $LOG
# once a second: Pi Home's Veille/Redémarrer requests (the launcher can't do either without
# root), the sleep timer, and every 2 minutes a reminder to the TV that the Pi is still here
(left=0; tick=0
while :; do
  sleep 1
  tick=$((tick + 1))
  if [ $((tick % 120)) -eq 0 ]; then
    cec --to 15 --report-physical-addr phys-addr=$PA,prim-devtype=record
    cec --to 15 --device-vendor-id vendor-id=0x00e091
  fi
  if [ $left -gt 0 ]; then
    left=$((left - 1))
    [ $left -eq 0 ] && input keyevent KEYCODE_SLEEP
  fi
  [ -f $REQ ] || continue
  r=$(cat $REQ); rm -f $REQ
  case "$r" in
    sleep) left=0; input keyevent KEYCODE_SLEEP;;
    "sleep-in "*) left=${r#sleep-in };;
    sleep-cancel) left=0;;
    reboot) svc power reboot || reboot;;
    # Pi Home's app updates: APKs it downloaded into its own files, installed in the background
    "install "*) (for f in ${r#install }; do
        echo "$(date) $f" >> $INSTLOG
        pm install -r "$f" >> $INSTLOG 2>&1 && rm -f "$f"
      done; chmod 644 $INSTLOG) &;;
  esac
done) &

op=
cec-ctl -d0 -M 2>&1 | while IFS= read -r line; do
  case "$line" in
    "Received from TV"*)
      echo "$line" >> $LOG
      op=
      case "$line" in
        *GIVE_DEVICE_POWER_STATUS*) cec --to 0 --report-power-status pwr-state=on;;
        *REQUEST_ACTIVE_SOURCE*) activate;;
        *MENU_REQUEST*) cec --to 0 --menu-status menu-state=activated;;
        *USER_CONTROL_PRESSED*|*VENDOR_COMMAND*|*GIVE_DECK_STATUS*|*SET_STREAM_PATH*) op="$line";;
      esac;;
    Received*|Transmitted*|Transmit\ *) op=;;
    *) if [ -n "$op" ]; then echo "$line" >> $LOG; handle "$op" "${line#*: }"; op=; fi;;
  esac
done
