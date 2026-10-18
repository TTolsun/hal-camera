#!/system/bin/sh
# Runs entirely on Android. The PC needs only adb.
set -u
URI=content://dev.halcamera.cli
LAST=/data/local/tmp/halcam-last-request

die() { echo "halcam: $*" >&2; exit 1; }
help_all() {
    cat <<'EOF'
HAL CAM — adb only
  doctor                                Check setup without starting a camera
  preview [start|stop] [--camera ID]
  capture [--camera ID]                  Save selected JPEG and RAW outputs
  record start [--camera ID] [--no-audio] Start recording and return when ready
  record stop                           Stop, wait for MP4, show download command
  streams [--camera ID] [--engine Camera2|CameraX]  List supported sizes
  cameras | probe | cts cases
  cts run --cases KEY[,KEY...]
  benchmark run [--camera ID] [--profile camera2-standard-v2]
                                        Run the fixed Camera2 benchmark; export JSON
  status [REQUEST_ID|--app|--last] | cancel [REQUEST_ID]
  requests list                         Find a lost request ID (newest first)
  fetch [REQUEST_ID]                     Prepare files and show one adb pull command
More tasks (start with the list command to find an ID):
  burst --count 3 [--interval-ms 500]     Save a fixed number of photos
  bracket                               Save three AEB photos and HDR when possible
  record snapshot                       Take a photo during CLI recording
  meter --x 0.5 --y 0.5 [--meter focus|exposure]
  live set --zoom 2 | live reset         Adjust current preview/CLI recording or reset controls
  live info | events                    Read callbacks / save Events ZIP
  pip sources                           Check candidates for the current Photo/Video preview
  capture --pip-type service --pip-camera ID
                                        Save PIP composition and source photos
  dual cameras                          List logical and physical camera IDs
  dual preview|capture|record --camera ID --first ID --second ID
                                        Dual video is silent; stop with record stop
  multi cameras                         List camera sets Multi can open together
  results list | results show --run ID
  results compare --run ID --reference ID
  results export --run ID                Export JSON and CSV
  baseline add|remove --run ID           Explicitly choose normal reference runs
  gallery list | gallery export --media ID
  incidents list | incidents export --incident ID
  incidents delete --incident ID --confirm true
  settings show | settings limit --limit 0|10|20|...|100
                                        Preview deletions; add --confirm true to apply
  benchmark run --build LABEL --commit SHA --branch NAME --note TEXT
  results delete --run ID --confirm true
  gallery delete --media ID --confirm true
Stream options for preview/capture/burst/bracket/record start:
  --engine Camera2|CameraX (default Camera2)
  --mode Photo|Video (preview and streams; default Photo)
  --preview-size WxH --yuv-size WxH|off --jpeg-size WxH|yuv|off
  --video-size WxH --video-fps FPS --codec H264|HEVC|Auto
  --raw-size WxH|off --fps FPS|MIN-MAX|auto
  --stabilization AUTO|OFF|OIS|VIDEO|PREVIEW
  --pip-type off|physical|service --pip-camera ID
  --pip-preview-size WxH --pip-photo-size WxH
  --pip-x X --pip-y Y --pip-width W --pip-height H (all four; 0–1)
For zoom, flash and manual controls: help controls
Omitted stream fields use defaults. CameraX recording codec is Auto.
Options: --timeout SECONDS (operation deadline), --no-wait (return request ID)
Default camera: 0. Microphone is enabled unless --no-audio is given.
Only one operation runs at a time. Unlock the device. ADB CLI is allowed by default.
Example: adb shell sh /data/local/tmp/halcam capture --camera 0
With multiple connections, use the same adb -s SERIAL for every command and pull.
Values with spaces: quote the whole command, because adb shell rejoins arguments:
  adb shell "sh /data/local/tmp/halcam benchmark run --note 'Same room'"
EOF
}
help() {
    cat <<'EOF'
HAL CAM: start with one task.
  1. Check connection: doctor
  2. Take a photo:     capture --camera 0
  3. Record a video:   record start --no-audio   (finish: record stop)
  4. Read results:     results list
  5. Find files:       gallery list
More commands: help all    Camera controls: help controls
After a saved result, copy the printed adb pull command to your PC terminal.
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
                *'"PREFLIGHT_FAILED"'*) echo 'Run streams for supported options. Adjust the named option, then retry.' >&2 ;;
                *'"CONFIRM_REQUIRED"'*) echo 'Read the ID with results list or gallery list. To delete just that item, add --confirm true.' >&2 ;;
                *'"BUSY"'*) echo 'Run status. Wait for completion, or use cancel to stop and keep saved files.' >&2 ;;
                *'"REQUEST_NOT_FOUND"'*) echo 'Find retained IDs with requests list; status --app shows the running one. Completed requests expire after 24 hours or 200 records.' >&2 ;;
            esac
            exit 1
        fi ;;
    esac
}
last_id() {
    [ -f "$LAST" ] || die 'No previous request on this device. Find one with requests list.'
    cat "$LAST"
}
validate_id() {
    echo "$1" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' || die 'Invalid request ID'
}
# The app describes each command in hello (command_specs), so this script lists only its own controls.
# Spec fields hold no nested objects, so one command's spec is the text between its braces.
json_list() { printf '%s' "$1" | sed -n "s/.*\"$2\":\[\([^]]*\)\].*/\1/p"; }
spec_of() {
    pattern=$(printf '%s' "$1" | sed 's/\./\\./g')
    printf '%s' "$hello" | sed -n "s/.*\"command_specs\":{.*\"$pattern\":{\([^}]*\)}.*/\1/p"
}
spec_has() { case "$spec" in *"\"$1\":true"*) return 0 ;; *) return 1 ;; esac; }
# Without a spec (an APK that predates command_specs) the app alone validates the option.
allowed_option() {
    case "$1" in ''|*[!a-z0-9_]*) return 1 ;; esac
    [ -n "$spec" ] || return 0
    case ",$(json_list "$spec" options)," in *",\"$1\","*) return 0 ;; esac
    case "$1" in engine) spec_has engine; return ;; mode) spec_has mode; return ;; esac
    spec_has streams || return 1
    keys=$(json_list "$hello" stream_keys)
    [ -n "$keys" ] || return 0
    case ",$keys," in *",\"$1\","*) return 0 ;; *) return 1 ;; esac
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
    last_progress=
    while :; do
        result=$(read_request "$rid")
        case "$result" in *'"completed":true'*) check_response "$result" true ;; *) check_response "$result" ;; esac
        state=$(echo "$result" | sed -n 's/.*"state":"\([a-z]*\)".*/\1/p')
        saved=$(echo "$result" | sed -n 's/.*"saved":\([0-9][0-9]*\).*/\1/p')
        progress="$state${saved:+: $saved photos saved}"
        if [ "$progress" != "$last_progress" ]; then echo "$progress"; last_progress=$progress; fi
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
        if spec_has recording; then
            case "$result" in *'"recording":true'*)
                echo "Recording started. Stop with: adb shell sh /data/local/tmp/halcam record stop"
                echo 'If using adb -s SERIAL, add the same -s SERIAL to the stop command.'
                return ;; esac
        fi
        [ "$(date +%s)" -lt "$end" ] || die "Wait timed out; operation may still run. Use status $rid."
        sleep 1
    done
}

[ $# -gt 0 ] || { help; exit 0; }
method=$1; shift
case "$method" in
    help|-h|--help)
        case "${1:-}" in
            controls) cat <<'EOF'
Photo controls (preview, capture, burst, bracket, record start):
  --zoom RATIO --ev STEPS --flash OFF|AUTO|ON|TORCH
  --ae-lock true|false --af-lock true|false
  --iso NUMBER --exposure-ns NANOSECONDS (use both)
  --focus DIOPTERS --wb AUTO|DAYLIGHT|CLOUDY|SHADE|CUSTOM
  --gains R,GE,GO,B --matrix M1,M2,M3,M4,M5,M6,M7,M8,M9
Run streams first. Unsupported values are rejected, never silently replaced.
EOF
            ;;
            all) help_all ;;
            *) help ;;
        esac
        exit 0 ;;
    preview)
        case "${1:-}" in start) shift ;; stop) method=preview.stop; shift ;; esac ;;
    record|cts|benchmark|results|baseline|gallery|dual|multi|live|pip|settings|incidents|requests)
        [ $# -gt 0 ] || die "$method needs a subcommand; use help"
        method=$method.$1; shift ;;
esac
hello=
spec=
case "$method" in
    doctor|status|cancel|fetch|live.set|live.reset|record.stop|record.snapshot|requests.list) ;;
    *)
        hello=$(content read --uri "$URI/v1/hello")
        check_response "$hello"
        commands=$(json_list "$hello" commands)
        if [ -n "$commands" ]; then
            case ",$commands," in *",\"$method\","*) ;; *) die "Unknown command: $method. Use help." ;; esac
            spec=$(spec_of "$method")
        fi ;;
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
    requests.list)
        [ $# -eq 0 ] || die 'requests list takes no options'
        result=$(content read --uri "$URI/v1/requests")
        check_response "$result"
        # Rows are flat objects inside the one array, so neither [ ] nor { } appear within a row.
        rows=$(printf '%s' "$result" | sed -n 's/.*"requests":\[\([^]]*\)\].*/\1/p' | grep -o '{[^{}]*}')
        if [ -z "$rows" ]; then echo 'No retained requests. Completed requests expire after 24 hours or 200 records.'; exit 0; fi
        echo 'Newest first: REQUEST_ID STATE COMMAND FILES'
        echo "$rows" | while read -r row; do
            id=$(echo "$row" | field_id)
            state=$(echo "$row" | sed -n 's/.*"state":"\([a-z]*\)".*/\1/p')
            name=$(echo "$row" | sed -n 's/.*"command":"\([a-z.]*\)".*/\1/p')
            files=$(echo "$row" | sed -n 's/.*"artifact_count":\([0-9]*\).*/\1/p')
            echo "$id $state $name ${files:-0}"
        done
        echo 'Next: status REQUEST_ID to inspect, fetch REQUEST_ID to prepare saved files. Do not repeat the capture.'
        exit 0 ;;
    status|cancel|fetch)
        [ $# -le 1 ] || die 'Expected at most one request ID'
        if [ "$method" = status ] && { [ "${1:-}" = --app ] || [ $# -eq 0 ]; }; then
            result=$(content read --uri "$URI/v1/status")
            check_response "$result"
            echo "$result"
            exit 0
        fi
        if [ "${1:-}" = --last ]; then
            [ "$method" = status ] || die '--last is only for status'
            rid=$(last_id)
        else rid=${1:-$(last_id)}; fi
        validate_id "$rid"
        if [ "$method" = cancel ]; then result=$(call --method request.cancel --arg "$rid")
        else result=$(read_request "$rid"); fi
        case "$result" in
            *'"completed":true'*) check_response "$result" true ;;
            *) check_response "$result" ;;
        esac
        echo "$result"
        if [ "$method" = fetch ]; then
            case "$result" in *'"completed":true'*) downloads "$result" ;; *) die 'Still running; use status first' ;; esac
        else
            case "$result" in
                *'"completed":true'*)
                    count=$(printf '%s' "$result" | grep -o '"artifact_id"' | wc -l | tr -d '[:space:]')
                    if [ "$count" -gt 0 ]; then
                        echo "Operation ended. $count saved file(s) remain available."
                        echo "Next: adb shell sh /data/local/tmp/halcam fetch $rid"
                        echo 'If using adb -s SERIAL, add the same -s SERIAL to the fetch command.'
                    else echo 'Operation ended. No files are registered for this request.'; fi
                    case "$result" in *'"error":{'*) exit 1 ;; esac ;;
            esac
        fi
        exit 0 ;;
    record.snapshot)
        [ $# -eq 0 ] || die 'record snapshot takes no options'
        result=$(call --method record.snapshot)
        check_response "$result"
        echo "$result"
        echo 'Snapshot requested. Use status to check snapshot_count or snapshot_error; record stop exports the files.'
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
seen_options=
unique_option() {
    case " $seen_options " in *" $1 "*) die "Repeated option: --$1" ;; esac
    seen_options="$seen_options $1"
}
wait_seconds=45
timeout=
camera=
profile=
audio=
cases=
stream_options=
build=
commit=
branch=
note=
while [ $# -gt 0 ]; do
    case "$1" in
        --build|--commit|--branch|--note)
            [ $# -ge 2 ] || die "$1 needs a value"
            [ "$method" = benchmark.run ] || die 'Build labels are only for benchmark run'
            unique_option "${1#--}"
            case "$2" in *:*) die 'Use the Python JSON client for labels containing colons' ;; esac
            case "$1" in --build) build=$2 ;; --commit) commit=$2 ;; --branch) branch=$2 ;; --note) note=$2 ;; esac
            shift 2 ;;
        --camera|--timeout|--cases|--profile)
            [ $# -ge 2 ] || die "$1 needs a value"
            [ -n "$2" ] || die "$1 needs a nonempty value"
            unique_option "${1#--}"
            case "$1" in --camera) camera=$2 ;; --timeout) timeout=$2 ;; --cases) cases=$2 ;; --profile) profile=$2 ;; esac
            shift 2 ;;
        --no-audio) audio=false; shift ;;
        --no-wait) no_wait=true; shift ;;
        --*)
            key=$(echo "${1#--}" | tr '-' '_')
            allowed_option "$key" || die "Unknown option: $1. Use help."
            [ $# -ge 2 ] || die "$1 needs a value"
            case "$2" in ''|*[!a-zA-Z0-9x.,_-]*) die 'Invalid stream option value' ;; esac
            unique_option "$key"
            stream_options="$stream_options $key:s:$2"
            shift 2 ;;
        *) die "Unknown option: $1. Use help." ;;
    esac
done
if [ -n "$profile" ]; then
    [ -z "$spec" ] || spec_has profile || die '--profile is only for benchmark run'
    [ "$profile" = camera2-standard-v2 ] || die 'Supported benchmark profile: camera2-standard-v2'
fi
[ -z "$audio" ] || [ -z "$spec" ] || spec_has audio || die '--no-audio is only for record start and dual record'
[ -z "$cases" ] || [ -z "$spec" ] || spec_has cases || die '--cases is only for cts run'
set -- --method "$method"
case "$method" in live.set|live.reset) ;; *) set -- "$@" --extra "request_id:s:$rid" ;; esac
[ -z "$build" ] || set -- "$@" --extra "build:s:$build"
[ -z "$commit" ] || set -- "$@" --extra "commit:s:$commit"
[ -z "$branch" ] || set -- "$@" --extra "branch:s:$branch"
[ -z "$note" ] || set -- "$@" --extra "note:s:$note"
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
elif ! spec_has recording; then
    # A recording returns once it starts; anything else may take its whole default deadline.
    default_ms=$(printf '%s' "$spec" | sed -n 's/.*"default_timeout_ms":\([0-9]*\).*/\1/p')
    [ -z "$default_ms" ] || wait_seconds=$((default_ms / 1000 + 15))
fi

if [ -z "$hello" ]; then
    hello=$(content read --uri "$URI/v1/hello")
    check_response "$hello"
fi
if spec_has foreground; then
    status=$(content read --uri "$URI/v1/status")
    check_response "$status"
    case "$status" in *'"busy":true'*) die 'Another operation is running. Use status or record stop.' ;; esac
    target_screen=live
    if spec_has current_screen; then
        case "$status" in *'"screen":"dual"'*) target_screen=dual ;; esac
    fi
    case "$status" in
        *\"screen\":\"$target_screen\"*) ;;
        *) am start -W -n dev.halcamera/.cli.CliLaunchActivity >/dev/null || die 'Unlock the device and open HAL CAM' ;;
    esac
    attempts=0
    while :; do
        status=$(content read --uri "$URI/v1/status")
        check_response "$status"
        case "$status" in *\"screen\":\"$target_screen\"*) break ;; esac
        attempts=$((attempts + 1))
        [ "$attempts" -lt 10 ] || die 'Unlock the device and open Live in HAL CAM'
        sleep 1
    done
fi
result=$(call "$@")
check_response "$result"
case "$method" in live.set|live.reset) echo "$result"; exit 0 ;; esac
echo "$rid" > "$LAST"
echo "request_id=$rid"
if [ "$no_wait" = true ]; then echo "$result"; else wait_for; fi
