# Launch 구간의 시스템 추적

`launch.pftxt`는 #204의 warm reopen 모드를 조사하기 위한 110초 수집 설정이다. scheduler, CPU frequency/idle, Binder, camera/HAL/AIDL 호출과 프로세스 이름을 수집한다. 앱이나 공통 판정 규칙을 바꾸지 않는다. 추적에는 다른 프로세스의 이름과 실행 이력도 포함되므로 원본은 공개 저장소에 커밋하지 않는다.

## 수집

1. 기기를 다른 작업에서 사용하지 않는지 확인한다. 같은 APK·camera·profile·전원·장면 조건을 유지하고 기존 APK와 측정 자료를 보존한다.
2. [공식 record_android_trace](https://github.com/google/perfetto/blob/main/tools/record_android_trace)를 내려받는다. Python 3과 `adb`가 필요하다. 앱의 Benchmark 설정 화면에서 아래 명령으로 수집을 시작한 뒤 `Start Benchmark`를 누른다.

   ```sh
   python record_android_trace --serial DEVICE -n -c tools/perfetto/launch.pftxt -o run-1.perfetto-trace
   ```

3. 화면에서 실행 완료를 확인하고 수집 명령이 종료될 때까지 기다린다. 실행을 반복할 때 파일 이름을 바꾼다. 다른 앱 설치나 화면 이탈, thermal 변화가 발생한 실행은 정상 반복에서 제외한다.
4. 추적을 끈 대조 실행도 같은 앱·profile로 수행한다. 한 번의 대조 실행으로 계측 영향이 없다고 확정하지 않는다. 두 모드가 수집되지 않으면 한 모드의 내부 대기만 분석할 수 있다.
5. 모든 반복을 끝낸 뒤 보고서를 Export로 회수한다. instrumentation 회수는 앱 프로세스를 재시작할 수 있으므로 반복 중간에 사용하지 않는다. 설치 전 APK를 복원한다.

## 분석

[공식 trace_processor](https://get.perfetto.dev/trace_processor)로 추적 파일을 로드한다. 아래 예시는 BOOTTIME 시계가 보존된 추적만 대상으로 한다.

```sh
node tools/perfetto/launch-windows.mjs runs.json > windows.sql
trace_processor server http --port 9104 run-1.perfetto-trace
# 별도 셸에서 같은 서버에 질의한다.
trace_processor query --remote 127.0.0.1:9104 -f windows.sql
trace_processor query --remote 127.0.0.1:9104 -f tools/perfetto/launch-analysis.sql
```

Windows에서는 내려받은 wrapper를 `python trace_processor ...`로 실행한다. 수집·서버 프로세스는 분석이 끝나면 종료한다. 서버를 외부 주소에 바인딩하지 않는다.

분석기는 단일 run JSON, JSON 배열, `HALCAM_REPORTS=` instrumentation 출력을 읽는다. 나노초 문자열을 부동소수점으로 변환하지 않고 SQL 정수로 보존한다. warmup·실패·미완료 구간은 제외하고 해당 trace 범위에 완전히 포함되는 open/configure/first_started 구간만 남긴다. 반복측정 보고서 개수와 남은 사이클 수를 대조하여 잘린 추적을 확인한다.

`clock_snapshot`에서 clock ID 6(BOOTTIME)의 `ts-clock_value`가 모두 0인지 먼저 확인한다. 그렇지 않으면 보고서 시각을 그대로 조인하지 않는다. `stats`의 데이터 유실·overrun과 수집 오류를 확인한다. SQL이 출력한 중첩 slice는 같은 시간을 포함할 수 있으므로 합산하지 않으며, 여러 스레드의 CPU 시간 합계도 wall time과 구분한다.

빠름/느림 경계 220ms는 #162의 탐색용 분류이다. 서로 다른 기기·profile·장면 조건을 합쳐 새 공통 규칙으로 사용하지 않는다. 긴 HAL 호출을 발견하더라도 빠른 모드와 비교하고, 호출 내부의 실행·대기 및 응답 경로를 확인하기 전에는 근본 원인으로 단정하지 않는다.

수집 설정의 의미는 [Perfetto 구성 문서](https://perfetto.dev/docs/concepts/config), 테이블과 질의 방법은 [Trace Processor 문서](https://perfetto.dev/docs/analysis/trace-processor)를 따른다.
