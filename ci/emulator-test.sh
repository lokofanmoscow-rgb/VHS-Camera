#!/usr/bin/env bash
# Installs the APK on the CI emulator, lets the app record a clip by itself
# (autotest mode) and collects the result for inspection.
set -x
APK=apk-dl/VHS-Camera.apk
PKG=com.vhscamera.app
OUT=ci-out
rm -rf "$OUT"; mkdir -p "$OUT"

adb install -r -g "$APK" || exit 1
adb shell settings put system accelerometer_rotation 0
adb logcat -c
adb shell am start -n $PKG/.MainActivity --ei autotest 8

done=0
for i in $(seq 1 120); do
  if adb logcat -d -s VHSCAM:I | grep -q AUTOTEST_DONE; then done=1; break; fi
  if [ "$i" = 5 ]; then adb exec-out screencap -p > "$OUT/screen_idle.png"; fi
  if [ "$i" = 12 ]; then adb exec-out screencap -p > "$OUT/screen_rec.png"; fi
  sleep 1
done
sleep 2
adb exec-out screencap -p > "$OUT/screen_after.png"
adb logcat -d -s VHSCAM:* VhsRenderer:* TapeRecorder:* CameraController:* AndroidRuntime:E > "$OUT/logcat.txt"
adb logcat -d | grep -i -E "vhscamera|MediaCodec|c2\.|AudioRecord|FATAL" | tail -300 > "$OUT/logcat_more.txt"
adb shell 'ls -la "/sdcard/DCIM/VHS Camera/"' > "$OUT/files.txt" 2>&1
adb shell content query --uri content://media/external/video/media \
  --projection _display_name:relative_path:is_pending:duration:width:height:_size > "$OUT/mediastore.txt" 2>&1
cat "$OUT/logcat.txt" | tail -60
cat "$OUT/files.txt" "$OUT/mediastore.txt"

F=$(adb shell 'ls "/sdcard/DCIM/VHS Camera/"' | tr -d '\r' | grep -i '\.mp4$' | tail -1)
if [ -z "$F" ] || [ "$done" != 1 ]; then echo "NO RECORDING PRODUCED"; exit 1; fi
adb pull "/sdcard/DCIM/VHS Camera/$F" "$OUT/sample.mp4" || exit 1

ffprobe -v error -show_entries format=duration,bit_rate:stream=index,codec_name,profile,width,height,avg_frame_rate,r_frame_rate,nb_frames,sample_rate,channels,bit_rate,start_time,duration \
  -of default=nw=1 "$OUT/sample.mp4" | tee "$OUT/ffprobe.txt"
ffmpeg -v error -y -i "$OUT/sample.mp4" -vf "fps=2,scale=480:360,tile=4x4" -frames:v 1 -q:v 4 "$OUT/sheet.jpg"
ffmpeg -v error -y -i "$OUT/sample.mp4" -vf "select='lt(n,12)',scale=480:360,tile=4x3" -frames:v 1 -vsync 0 -q:v 4 "$OUT/start.jpg"
ffmpeg -v error -y -sseof -0.4 -i "$OUT/sample.mp4" -vf "scale=480:360,tile=4x3" -frames:v 1 -q:v 4 "$OUT/end.jpg"
ffmpeg -v error -y -ss 1.0 -i "$OUT/sample.mp4" -frames:v 1 -q:v 2 "$OUT/frame_1s.jpg"
ffmpeg -v error -y -ss 5.0 -i "$OUT/sample.mp4" -frames:v 1 -q:v 2 "$OUT/frame_5s.jpg"
ffmpeg -v error -y -i "$OUT/sample.mp4" -vn -c:a copy "$OUT/audio.m4a"
ffmpeg -v error -i "$OUT/sample.mp4" -af volumedetect -vn -f null - 2>&1 | grep -E "mean_volume|max_volume" | tee "$OUT/volume.txt"
ffprobe -v error -select_streams v:0 -show_entries frame=pts_time -of csv=p=0 "$OUT/sample.mp4" | head -400 > "$OUT/video_pts.txt"
ls -la "$OUT"
