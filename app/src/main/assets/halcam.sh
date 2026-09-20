#!/system/bin/sh
# Runs entirely on Android. The PC needs only adb.
set -u
URI=content://dev.halcamera.cli
LAST=/data/local/tmp/halcam-last-request

die() { echo "halcam: $*" >&2; exit 1; }
help() {
    cat <<'EOF'
HAL CAM — adb only
  preview [start|stop] [--camera ID]
  capture [--camera ID]                  Save YUV + JPEG photos
  record start [--camera ID] [--no-audio] Start recording and return when ready
  record stop                           Stop, wait for MP4, show download command
  cameras | probe | cts cases
  cts run --cases KEY[,KEY...]
  status [REQUEST_ID] | cancel [REQUEST_ID]
  fetch [REQUEST_ID]                     Prepare files and show one adb pull command
Options: --timeout SECONDS (operation deadline), --no-wait (return request ID)
Default camera: 0. Microphone is enabled unless --no-audio is given.
Only one operation runs at a time. Unlock the device and allow ADB CLI in the app.
Example: adb shell sh /data/local/tmp/halcam capture --camera 0
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
        *'"error":{'*) if [ "${2:-false}" != true ]; then echo "$1" >&2; exit 1; fi ;;
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
}
wait_for() {
    # A bounded client wait never cancels accepted work; status/fetch can recover it.
    end=$(( $(date +%s) + wait_seconds ))
    while :; do
        result=$(read_request "$rid")
        check_response "$result"
        case "$result" in
            *'"completed":true'*)
                case "$method" in
                    preview) echo 'Preview is ready.' ;;
                    preview.stop) echo 'Preview stopped.' ;;
                    capture) echo 'Saved two photos (YUV and JPEG).' ;;
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
    record|cts)
        [ $# -gt 0 ] || die "$method needs a subcommand; use help"
        method=$method.$1; shift ;;
esac
case "$method" in
    cameras|preview|preview.stop|capture|record.start|record.stop|probe|cts.cases|cts.run|status|cancel|fetch) ;;
    *) die "Unknown command: $method. Use help. Benchmark is available in the app only." ;;
esac

case "$method" in
    status|cancel|fetch)
        [ $# -le 1 ] || die 'Expected at most one request ID'
        if [ "$method" = status ] && [ $# -eq 0 ] && [ ! -f "$LAST" ]; then
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
audio=
cases=
while [ $# -gt 0 ]; do
    case "$1" in
        --camera|--timeout|--cases)
            [ $# -ge 2 ] || die "$1 needs a value"
            [ -n "$2" ] || die "$1 needs a nonempty value"
            case "$1" in --camera) camera=$2 ;; --timeout) timeout=$2 ;; --cases) cases=$2 ;; esac
            shift 2 ;;
        --no-audio) audio=false; shift ;;
        --no-wait) no_wait=true; shift ;;
        *) die "Unknown option: $1. Use help." ;;
    esac
done
set -- --method "$method" --extra "request_id:s:$rid"
if [ -n "$camera" ]; then
    case "$camera" in *[!a-zA-Z0-9_.-]*) die 'Invalid camera ID' ;; esac
    set -- "$@" --extra "camera:s:$camera"
fi
[ -z "$audio" ] || set -- "$@" --extra "audio:b:false"
[ -z "$cases" ] || set -- "$@" --extra "cases:s:$cases"
if [ -n "$timeout" ]; then
    case "$timeout" in *[!0-9]*|'') die 'Timeout must be 1–3600 seconds' ;; esac
    timeout=$(echo "$timeout" | sed 's/^0*//')
    [ -n "$timeout" ] || die 'Timeout must be 1–3600 seconds'
    [ "$timeout" -ge 1 ] && [ "$timeout" -le 3600 ] || die 'Timeout must be 1–3600 seconds'
    set -- "$@" --extra "timeout_ms:l:$((timeout * 1000))"
    wait_seconds=$((timeout + 15))
elif [ "$method" = cts.run ]; then wait_seconds=1815
fi

hello=$(content read --uri "$URI/v1/hello")
check_response "$hello"
case "$method" in
    preview|preview.stop|capture|record.start|cts.run)
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
