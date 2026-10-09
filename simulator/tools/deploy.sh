#!/usr/bin/env bash
# ResiLane phone deployment over the vivo/iQOO Office Kit connection.
#
# Office Kit (pcsuite) runs its own adb server on :5037 and keeps the phones connected. We talk to
# that server with Office Kit's OWN adb.exe: a newer platform-tools adb would see a version
# mismatch, kill Office Kit's server, and drop its connection.
#
# Usage: tools/deploy.sh <command>
#   devices   list phones: model, Android, ARCore version, wireless IP
#   build     ./gradlew :app:assembleDebug
#   install   build if needed, install on every phone (granting permissions), launch
#   launch    (re)start the app on every phone
#   stop      force-stop the app everywhere
#   wifi      switch USB-attached phones to wireless adb (so people can walk around), then connect
#   logs      stream RL/* logs from every phone, prefixed with the phone
#   pull      copy each phone's session logs + datasets to tools/out/sessions/<phone>/
#   shot      screenshot every phone to tools/out/shots/ and run the YOLO check on them
#   arcore    check every phone has Google Play Services for AR
#   send      no adb needed: push the APK to every phone linked in Office Kit (Wi-Fi or USB) into
#             Download/Office Kit/ on the phone; tap it there to install
#
# Override the adb path with ADB=..., target phones with SERIALS="a b".
set -uo pipefail
cd "$(dirname "$0")/.."

ADB="${ADB:-/c/Program Files (x86)/pcsuite/adb/adb.exe}"
[ -x "$ADB" ] || ADB="$(command -v adb || true)"
[ -n "$ADB" ] || { echo "adb not found (Office Kit not installed?)"; exit 1; }
PKG=org.resilane.sim
APK=app/build/outputs/apk/debug/app-debug.apk
PY=tools/.venv/Scripts/python.exe

adb() { "$ADB" "$@"; }
serials() {
  if [ -n "${SERIALS:-}" ]; then echo "$SERIALS"; return; fi
  adb devices | awk 'NR>1 && $2=="device" {print $1}'
}
label() { echo "$(adb -s "$1" shell getprop ro.product.model | tr -d '\r ')-${1##*:}" | tr -c 'A-Za-z0-9._-\n' '_'; }
need_phones() {
  local s; s=$(serials)
  if [ -z "$s" ]; then
    echo "No phones attached. In Office Kit: connect each phone (USB or WLAN) and allow USB debugging."
    adb devices -l
    exit 1
  fi
}

cmd_devices() {
  adb devices -l
  for s in $(serials); do
    local model rel arcore ip
    model=$(adb -s "$s" shell getprop ro.product.model | tr -d '\r')
    rel=$(adb -s "$s" shell getprop ro.build.version.release | tr -d '\r')
    arcore=$(adb -s "$s" shell dumpsys package com.google.ar.core | grep -m1 versionName | tr -d '\r ' )
    ip=$(adb -s "$s" shell ip -f inet addr show wlan0 2>/dev/null | awk '/inet /{print $2}' | cut -d/ -f1 | tr -d '\r')
    printf '%-24s %-18s Android %-4s ARCore %-28s wlan %s\n' "$s" "$model" "$rel" "${arcore:-MISSING}" "${ip:--}"
  done
}

cmd_build() { ./gradlew :app:assembleDebug --console=plain -q; }

cmd_install() {
  need_phones
  [ -f "$APK" ] || cmd_build || exit 1
  local fails=0
  for s in $(serials); do
    echo "== $s ($(adb -s "$s" shell getprop ro.product.model | tr -d '\r'))"
    if adb -s "$s" install -r -g "$APK"; then
      adb -s "$s" shell am start -n "$PKG/.MainActivity" >/dev/null
    else
      fails=$((fails + 1))
    fi
  done
  return $fails
}

cmd_launch() {
  need_phones
  for s in $(serials); do
    adb -s "$s" shell am force-stop "$PKG"
    adb -s "$s" shell am start -n "$PKG/.MainActivity" >/dev/null && echo "launched on $s"
  done
}

cmd_stop() { for s in $(serials); do adb -s "$s" shell am force-stop "$PKG"; done; }

cmd_wifi() {
  need_phones
  for s in $(serials); do
    case "$s" in *:*) echo "$s already wireless"; continue ;; esac
    local ip
    ip=$(adb -s "$s" shell ip -f inet addr show wlan0 | awk '/inet /{print $2}' | cut -d/ -f1 | tr -d '\r')
    [ -n "$ip" ] || { echo "$s: no Wi-Fi IP (join the same Wi-Fi as this PC)"; continue; }
    adb -s "$s" tcpip 5555 && sleep 2 && adb connect "$ip:5555"
  done
  echo "Unplug USB when the phones show up as <ip>:5555 below."
  adb devices
}

cmd_logs() {
  need_phones
  for s in $(serials); do
    local tag; tag=$(label "$s")
    adb -s "$s" logcat -c
    ( adb -s "$s" logcat -v time -s 'RL/App' 'RL/Calib' 'RL/Track' 'RL/Peer' 'RL/Net' 'RL/Yolo' 'RL/Sim' 'RL/Model' 'RL/GL' 'RL/Data' \
      | sed -u "s/^/[$tag] /" ) &
  done
  wait
}

cmd_pull() {
  need_phones
  for s in $(serials); do
    local d; d="tools/out/sessions/$(label "$s")"
    mkdir -p "$d"
    adb -s "$s" pull "/sdcard/Android/data/$PKG/files/sessions" "$d" && echo "-> $d"
  done
}

cmd_shot() {
  need_phones
  local dir; dir="tools/out/shots/$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$dir"
  for s in $(serials); do
    adb -s "$s" exec-out screencap -p > "$dir/$(label "$s").png" && echo "-> $dir/$(label "$s").png"
  done
  [ -x "$PY" ] && "$PY" tools/yolo_check.py "$dir" || true
}

cmd_arcore() {
  need_phones
  for s in $(serials); do
    if adb -s "$s" shell pm list packages | grep -q com.google.ar.core; then
      echo "$s: Google Play Services for AR installed"
    else
      echo "$s: MISSING Google Play Services for AR. The app will prompt to install it from Play Store."
    fi
  done
}

# Office Kit's own "send to vivo device" (Explorer right-click item), see tools/send-officekit.ps1.
cmd_send() {
  [ -f "$APK" ] || cmd_build || exit 1
  local ver; ver=$(grep -o 'versionName = "[^"]*"' app/build.gradle.kts | cut -d'"' -f2)
  mkdir -p dist && cp "$APK" "dist/ResiLane-$ver.apk"
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/send-officekit.ps1 "dist/ResiLane-$ver.apk"
}

c="${1:-devices}"; shift || true
case "$c" in
  devices|build|install|launch|stop|wifi|logs|pull|shot|arcore|send) "cmd_$c" "$@" ;;
  *) sed -n '2,25p' "$0"; exit 1 ;;
esac
