#!/system/bin/sh
# Runs entirely on Android. The PC needs only adb.
set -u
URI=content://dev.halcamera.cli
LAST=/data/local/tmp/halcam-last-request

die() { echo "halcam: $*" >&2; exit 1; }
help() {
    cat <<'EOF'
HAL CAM — adb only
  doctor                                Check setup without starting a camera
  preview [start|stop] [--camera ID]
  capture [--camera ID]                  Save YUV + JPEG photos
  record start [--camera ID] [--no-audio] Start recording and return when ready
  record stop                           Stop, wait for MP4, show download command
  streams [--camera ID] [--engine Camera2|CameraX]  List supported sizes
  cameras | probe | cts cases
  cts run --cases KEY[,KEY...]
  benchmark run [--camera ID] [--profile camera2-standard-v2]
                                        Run the fixed Camera2 benchmark; export JSON
  status [REQUEST_ID|--app] | cancel [REQUEST_ID]
  fetch [REQUEST_ID]                     Prepare files and show one adb pull command
Stream options for preview/capture/record start:
  --engine Camera2|CameraX (default Camera2)
  --preview-size WxH --yuv-size WxH|off --jpeg-size WxH|off
  --video-size WxH --video-fps FPS --codec H264|HEVC|Auto
Omitted stream fields use defaults. CameraX recording codec is Auto.
Options: --timeout SECONDS (operation deadline), --no-wait (return request ID)
Default camera: 0. Microphone is enabled unless --no-audio is given.
Only one operation runs at a time. Unlock the device. ADB CLI is allowed by default.
Example: adb shell sh /data/local/tmp/halcam capture --camera 0
With multiple connections, use the same adb -s SERIAL for every command and pull.
EOF
}
call() {
    content call --uri "$URI" "$@" | sed -e 's/^Result: Bundle\[{json=//' -e 's/}\]$//'
}
read_request() { content read --uri "$URI/v1/requests/$1"; }
field_id() { sed -n 's/.*"request_id":"\([0-9a-f-]*\)".*/\1/p'; }
check_response() {
    case "$1" in
        *'"protocol_version":1'*) ;;
        *) die "Cannot reach HAL CAM. Install the APK and run this command again. $1" ;;
    esac
    case "$1" in
        *'"error":{'*) if [ "${2:-false}" != true ]; then
            echo "$1" >&2
            case "$1" in
                *'"CLI_DISABLED"'*) echo 'Enable ADB CLI in HAL CAM: Lab > ADB CLI.' >&2 ;;
                *'"PERMISSION_REQUIRED"'*) echo 'Allow the required permission in the app. For silent recording use --no-audio.' >&2 ;;
                *'"DEVICE_LOCKED"'*) echo 'Unlock the device, then retry.' >&2 ;;
                *'"REQUEST_NOT_FOUND"'*) echo 'Use status --app for current app state. Completed requests expire after 24 hours or 200 records.' >&2 ;;
            esac
            exit 1
        fi ;;
    esac
}
last_id() {
    [ -f "$LAST" ] || die 'No previous request. Run capture, preview, or record start first.'
    cat "$LAST"
}
validate_id() {
    echo "$1" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' || die 'Invalid request ID'
}
downloads() {
    case "$1" in *'"artifact_id":'*) ;; *) return ;; esac
    dest=/sdcard/Download/HALCamera-cli/$rid
    mkdir -p "$dest" || die 'Cannot create the download folder on the device'
    manifest=$(content read --uri "$URI/v1/requests/$rid/files") || die 'Cannot read file list; retry fetch'
    [ -n "$manifest" ] || die 'File list unavailable; retry fetch'
    tab=$(printf '\t')
    echo "$manifest" | while IFS="$tab" read -r aid name bytes hash; do
        case "$aid" in file-[0-9]*) ;; *) exit 1 ;; esac
        case "$name" in ''|*[!a-zA-Z0-9_.-]*|*..*|.*) exit 1 ;; esac
        case "$bytes" in ''|*[!0-9]*) exit 1 ;; esac
        # No PC redirection: Windows PowerShell cannot corrupt these binary files.
        content read --uri "$URI/v1/requests/$rid/artifacts/$aid" > "$dest/$name.part" || exit 1
        actual=$(sha256sum "$dest/$name.part" | cut -d ' ' -f 1)
        [ "$actual" = "$hash" ] && [ "$(wc -c < "$dest/$name.part")" -eq "$bytes" ] || exit 1
        mv "$dest/$name.part" "$dest/$name" || exit 1
    done || die "Could not prepare all files; retry fetch $rid"
    echo "Files ready. Copy to this PC with:"
    echo "adb pull $dest ."
    echo 'If using adb -s SERIAL, add the same -s SERIAL to the pull command.'
}
wait_for() {
    # A bounded client wait never cancels accepted work; status/fetch can recover it.
    end=$(( $(date +%s) + wait_seconds ))
    while :; do
        result=$(read_request "$rid")
        check_response "$result"
        case "$result" in
            *'"completed":true'*)
                case "$result" in
                    *'"state":"succeeded"'*) ;;
                    *) echo "$result" >&2; die "Request did not succeed. Use fetch $rid to recover any saved files." ;;
                esac
                case "$method" in
                    preview) echo 'Preview is ready.' ;;
                    preview.stop) echo 'Preview stopped.' ;;
                    capture) echo 'Photos saved.' ;;
                    record.stop) echo 'Recording saved.' ;;
                    probe) echo 'Camera specifications saved (JSON and TXT).' ;;
                    *) echo "$result" ;;
                esac
                downloads "$result"
                return ;;
        esac
        if [ "$method" = record.start ]; then
            case "$result" in *'"recording":true'*) echo "Recording started. Stop with: adb shell sh /data/local/tmp/halcam record stop"; return ;; esac
        fi
        [ "$(date +%s)" -lt "$end" ] || die "Wait timed out; operation may still run. Use status $rid."
        sleep 1
    done
}

[ $# -gt 0 ] || { help; exit 0; }
method=$1; shift
case "$method" in
    help|-h|--help) help; exit 0 ;;
    preview)
        case "${1:-}" in start) shift ;; stop) method=preview.stop; shift ;; esac ;;
    record|cts|benchmark)
        [ $# -gt 0 ] || die "$method needs a subcommand; use help"
        method=$method.$1; shift ;;
esac
case "$method" in
    doctor|streams|cameras|preview|preview.stop|capture|record.start|record.stop|probe|cts.cases|cts.run|benchmark.run|status|cancel|fetch) ;;
    *) die "Unknown command: $method. Use help." ;;
esac

case "$method" in
    doctor)
        [ $# -eq 0 ] || die 'doctor takes no options'
        hello=$(content read --uri "$URI/v1/hello")
        check_response "$hello"
        echo "$hello"
        ready=true
        case "$hello" in
            *'"camera_permission":true'*) ;;
            *) echo 'Allow camera access in HAL CAM.' >&2; ready=false ;;
        esac
        case "$hello" in
            *'"locked":false'*) ;;
            *) echo 'Unlock the device before camera operations.' >&2; ready=false ;;
        esac
        result=$(content read --uri "$URI/v1/status")
        check_response "$result"
        echo "$result"
        case "$result" in
            *'"busy":true'*) echo 'Another operation is running. Use status --app to inspect it.' >&2; ready=false ;;
        esac
        [ "$ready" = true ] || exit 1
        echo 'Basic setup is ready. Recording with audio also needs microphone permission; Android 8-9 saving needs storage permission.'
        exit 0 ;;
    status|cancel|fetch)
        [ $# -le 1 ] || die 'Expected at most one request ID'
        if [ "$method" = status ] && { [ "${1:-}" = --app ] || { [ $# -eq 0 ] && [ ! -f "$LAST" ]; }; }; then
            result=$(content read --uri "$URI/v1/status")
            check_response "$result"
            echo "$result"
            exit 0
        fi
        rid=${1:-$(last_id)}
        validate_id "$rid"
        if [ "$method" = cancel ]; then result=$(call --method request.cancel --arg "$rid")
        else result=$(read_request "$rid"); fi
        if [ "$method" = fetch ]; then check_response "$result" true
        else check_response "$result"; fi
        echo "$result"
        if [ "$method" = fetch ]; then
            case "$result" in *'"completed":true'*) downloads "$result" ;; *) die 'Still running; use status first' ;; esac
        fi
        exit 0 ;;
    record.stop)
        [ $# -eq 0 ] || die 'record stop takes no options'
        result=$(call --method record.stop)
        check_response "$result"
        rid=$(echo "$result" | field_id)
        validate_id "$rid"
        wait_seconds=60
        wait_for
        exit 0 ;;
esac

rid=$(cat /proc/sys/kernel/random/uuid)
validate_id "$rid"
no_wait=false
wait_seconds=45
timeout=
camera=
profile=
audio=
cases=
stream_options=
while [ $# -gt 0 ]; do
    case "$1" in
        --engine|--preview-size|--yuv-size|--jpeg-size|--video-size|--video-fps|--codec)
            [ $# -ge 2 ] || die "$1 needs a value"
            case "$2" in ''|*[!a-zA-Z0-9x]*) die 'Invalid stream option value' ;; esac
            key=$(echo "${1#--}" | tr '-' '_')
            stream_options="$stream_options $key:s:$2"
            shift 2 ;;
        --camera|--timeout|--cases|--profile)
            [ $# -ge 2 ] || die "$1 needs a value"
            [ -n "$2" ] || die "$1 needs a nonempty value"
            case "$1" in --camera) camera=$2 ;; --timeout) timeout=$2 ;; --cases) cases=$2 ;; --profile) profile=$2 ;; esac
            shift 2 ;;
        --no-audio) audio=false; shift ;;
        --no-wait) no_wait=true; shift ;;
        *) die "Unknown option: $1. Use help." ;;
    esac
done
if [ -n "$profile" ]; then
    [ "$method" = benchmark.run ] || die '--profile is only for benchmark run'
    [ "$profile" = camera2-standard-v2 ] || die 'Supported benchmark profile: camera2-standard-v2'
fi
if [ "$method" = benchmark.run ]; then
    [ -z "$stream_options$audio$cases" ] || die 'Benchmark uses a fixed Camera2 profile; stream, audio and CTS options are not supported'
fi
set -- --method "$method" --extra "request_id:s:$rid"
[ -z "$profile" ] || set -- "$@" --extra "profile:s:$profile"
if [ -n "$camera" ]; then
    case "$camera" in *[!a-zA-Z0-9_.-]*) die 'Invalid camera ID' ;; esac
    set -- "$@" --extra "camera:s:$camera"
fi
for option in $stream_options; do set -- "$@" --extra "$option"; done
[ -z "$audio" ] || set -- "$@" --extra "audio:b:false"
# Suite keys hold colons, which --extra key:type:value cannot carry, so they travel as --arg.
[ -z "$cases" ] || set -- "$@" --arg "$cases"
if [ -n "$timeout" ]; then
    case "$timeout" in *[!0-9]*|'') die 'Timeout must be 1–3600 seconds' ;; esac
    timeout=$(echo "$timeout" | sed 's/^0*//')
    [ -n "$timeout" ] || die 'Timeout must be 1–3600 seconds'
    [ "$timeout" -ge 1 ] && [ "$timeout" -le 3600 ] || die 'Timeout must be 1–3600 seconds'
    set -- "$@" --extra "timeout_ms:l:$((timeout * 1000))"
    wait_seconds=$((timeout + 15))
elif [ "$method" = cts.run ]; then wait_seconds=1815
elif [ "$method" = benchmark.run ]; then wait_seconds=615
fi

hello=$(content read --uri "$URI/v1/hello")
check_response "$hello"
case "$method" in
    preview|preview.stop|capture|record.start|cts.run|benchmark.run)
        status=$(content read --uri "$URI/v1/status")
        check_response "$status"
        case "$status" in *'"busy":true'*) die 'Another operation is running. Use status or record stop.' ;; esac
        case "$status" in
            *'"screen":"live"'*) ;;
            *) am start -W -n dev.halcamera/.cli.CliLaunchActivity >/dev/null || die 'Unlock the device and open HAL CAM' ;;
        esac
        attempts=0
        while :; do
            status=$(content read --uri "$URI/v1/status")
            check_response "$status"
            case "$status" in *'"screen":"live"'*) break ;; esac
            attempts=$((attempts + 1))
            [ "$attempts" -lt 10 ] || die 'Unlock the device and open Live in HAL CAM'
            sleep 1
        done ;;
esac
result=$(call "$@")
check_response "$result"
echo "$rid" > "$LAST"
echo "request_id=$rid"
if [ "$no_wait" = true ]; then echo "$result"; else wait_for; fi
