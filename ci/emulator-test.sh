#!/usr/bin/env bash
# Installs the APK on the CI emulator, lets the app record clips by itself
# (autotest mode) and collects the results for inspection.
set -x
APK=apk-dl/VHS-Camera.apk
PKG=com.vhscamera.app
OUT=ci-out
ALBUM="/sdcard/DCIM/VHS Camera"
rm -rf "$OUT"; mkdir -p "$OUT"

adb install -r -g "$APK" || exit 1
adb shell settings put system accelerometer_rotation 0
# Android's one-time "Viewing full screen" hint would swallow the test's touches.
adb shell settings put secure immersive_mode_confirmations confirmed
# The slow CI emulator sometimes reports its own launcher as "not responding"; keep that dialog away.
adb shell settings put global hide_error_dialogs 1

# Turn the virtual phone on its side, the way a camcorder app is held.
rotate_device() {
  adb emu rotate
  sleep 4
  adb shell dumpsys window displays | grep -i -m 3 -E "mCurrentRotation|rotation=|mRotation" || true
}

# run_case NAME [extra am-start args...]: one autotest recording, results in $OUT/NAME_*
run_case() {
  local name=$1; shift
  adb shell am force-stop $PKG
  adb shell "rm -rf '$ALBUM'"
  adb logcat -c
  adb shell am start -n $PKG/.MainActivity --ei autotest 8 "$@"
  local done=0
  for i in $(seq 1 150); do
    if adb logcat -d -s VHSCAM:I | grep -q AUTOTEST_DONE; then done=1; break; fi
    if [ "$i" = 14 ]; then adb exec-out screencap -p > "$OUT/${name}_screen_rec.png"; fi
    sleep 1
  done
  sleep 2
  # Open the on-screen menu (MENU key, top of the left side of the body) for a look at it.
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1 || true
  adb exec-out screencap -p > "$OUT/${name}_screen_idle.png"
  adb shell input tap 240 270
  sleep 1
  adb exec-out screencap -p > "$OUT/${name}_screen_menu.png"
  # MODE is the first line of the menu: switch the look (it is remembered for the next case).
  adb shell input tap 1200 391
  sleep 1
  adb exec-out screencap -p > "$OUT/${name}_screen_mode.png"
  adb shell input tap 240 270
  adb logcat -d -s VHSCAM:* VhsRenderer:* TapeRecorder:* CameraController:* AndroidRuntime:E > "$OUT/${name}_logcat.txt"
  adb shell dumpsys window displays | grep -i -m 3 -E "mCurrentRotation|rotation=|mRotation" >> "$OUT/${name}_logcat.txt" || true
  adb shell "ls -la '$ALBUM/'" > "$OUT/${name}_files.txt" 2>&1
  adb shell content query --uri content://media/external/video/media \
    --projection _display_name:relative_path:is_pending:duration:width:height:_size > "$OUT/${name}_mediastore.txt" 2>&1
  cat "$OUT/${name}_logcat.txt" | tail -40
  cat "$OUT/${name}_files.txt" "$OUT/${name}_mediastore.txt"

  local f
  f=$(adb shell "ls '$ALBUM/'" | tr -d '\r' | grep -i '\.mp4$' | tail -1)
  if [ -z "$f" ] || [ "$done" != 1 ]; then echo "CASE $name: NO RECORDING PRODUCED"; return 1; fi
  adb pull "$ALBUM/$f" "$OUT/${name}.mp4" || return 1
  local v="$OUT/${name}.mp4"
  ffprobe -v error -show_entries format=duration,bit_rate:stream=index,codec_name,profile,width,height,avg_frame_rate,r_frame_rate,nb_frames,sample_rate,channels,bit_rate,start_time,duration \
    -of default=nw=1 "$v" | tee "$OUT/${name}_ffprobe.txt"
  ffmpeg -v error -y -i "$v" -vf "fps=2,scale=480:360,tile=4x4" -frames:v 1 -q:v 4 "$OUT/${name}_sheet.jpg"
  ffmpeg -v error -y -i "$v" -vf "select='lt(n,12)',scale=480:360,tile=4x3" -frames:v 1 -vsync 0 -q:v 4 "$OUT/${name}_start.jpg"
  ffmpeg -v error -y -sseof -0.6 -i "$v" -vf "scale=480:360,tile=4x3" -frames:v 1 -q:v 4 "$OUT/${name}_end.jpg"
  ffmpeg -v error -y -ss 1.0 -i "$v" -frames:v 1 -q:v 2 "$OUT/${name}_frame_1s.jpg"
  ffmpeg -v error -y -ss 5.0 -i "$v" -frames:v 1 -q:v 2 "$OUT/${name}_frame_5s.jpg"
  ffmpeg -v error -y -i "$v" -vf "scale=320:240,tile=8x6" -frames:v 1 -vsync 0 -q:v 5 "$OUT/${name}_all.jpg"
  ffmpeg -v error -y -i "$v" -vn -c:a copy "$OUT/${name}_audio.m4a"
  ffprobe -v error -select_streams v:0 -show_entries frame=pts_time -of csv=p=0 "$v" | head -400 > "$OUT/${name}_video_pts.txt"
  # A clip must have a picture and a sound track of sensible length.
  local dur
  dur=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$v")
  # The software-rendered CI emulator delivers only a few frames per second, so the
  # eight closing frames alone can take several seconds: allow a wide margin.
  awk -v d="$dur" 'BEGIN { exit !(d > 5.0 && d < 40.0) }' || { echo "CASE $name: bad duration $dur"; return 1; }
  grep -q "codec_name=h264" "$OUT/${name}_ffprobe.txt" || { echo "CASE $name: no video"; return 1; }
  grep -q "codec_name=aac" "$OUT/${name}_ffprobe.txt" || { echo "CASE $name: no audio"; return 1; }
  return 0
}

status=0
# The app drawer, to see the launcher icon.
adb shell input keyevent KEYCODE_HOME
sleep 3
adb shell input swipe 540 1900 540 500 400
sleep 3
adb exec-out screencap -p > "$OUT/launcher.png"
rotate_device
run_case back || status=1
# The other landscape orientation (upside-down relative to the first), front camera.
rotate_device
rotate_device
run_case front --ez front true || status=1
ls -la "$OUT"
exit $status
