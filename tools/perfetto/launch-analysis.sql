-- First load launch-windows.mjs output in the same trace_processor session.
-- Do not interpret windows unless BOOTTIME rows below have offset_ns = 0.
SELECT clock_name, count(*) AS samples, min(ts-clock_value) AS min_offset_ns,
  max(ts-clock_value) AS max_offset_ns
FROM clock_snapshot WHERE clock_id=6 GROUP BY clock_name;

SELECT name, value, severity FROM stats
WHERE value != 0 AND (severity != 'info' OR name GLOB '*overrun*' OR name GLOB '*loss*');

-- Every measured window must fit entirely inside this trace; warmup/failed omitted.
SELECT * FROM launch_windows ORDER BY ts, stage;

-- Thread time is clipped to the stage. Totals across multiple threads are not wall time.
SELECT w.run_id, w.iteration, w.mode, w.stage, p.name AS process_name,
  t.name AS thread_name, t.tid, t.utid, s.state,
  sum(min(s.ts+s.dur,w.end_ts)-max(s.ts,w.ts))/1e6 AS duration_ms
FROM launch_windows w
JOIN thread_state s ON s.dur>0 AND s.ts<w.end_ts AND s.ts+s.dur>w.ts
JOIN thread t USING(utid) JOIN process p USING(upid)
WHERE p.name='dev.halcamera' AND (t.is_main_thread=1 OR t.name='CD.Camera2')
GROUP BY w.run_id,w.iteration,w.stage,t.utid,s.state
ORDER BY w.ts,t.utid,duration_ms DESC;

INCLUDE PERFETTO MODULE slices.with_context;
-- A nested call overlaps its parent: do not sum these rows as independent work.
SELECT w.run_id,w.iteration,w.mode,w.stage,s.id,s.parent_id,s.name,
  s.process_name,s.thread_name,s.utid,s.ts,s.dur/1e6 AS full_duration_ms,
  (min(s.ts+s.dur,w.end_ts)-max(s.ts,w.ts))/1e6 AS overlap_ms
FROM launch_windows w JOIN thread_or_process_slice s
  ON s.dur>0 AND s.ts<w.end_ts AND s.ts+s.dur>w.ts
WHERE w.stage='first_started' AND (
  s.name='reconfigureCamera' OR s.name='[CAMKPI] camera3->configure_streams'
  OR s.name='first_frame' OR s.name='CameraHal::processBatchCaptureRequests'
  OR s.name='AIDL::java::ICameraDeviceCallbacks::onCaptureStarted::server')
ORDER BY w.ts,s.ts;

-- Carry forward the last recorded CPU frequency, including one before a window.
WITH frequency AS (
  SELECT c.ts, lead(c.ts,1,(SELECT end_ts FROM trace_bounds))
    OVER(PARTITION BY c.track_id ORDER BY c.ts) AS end_ts, t.cpu, c.value
  FROM counter c JOIN cpu_counter_track t ON t.id=c.track_id
  WHERE t.name='cpufreq'
)
SELECT w.run_id,w.iteration,w.mode,f.cpu,min(f.value) AS min_khz,max(f.value) AS max_khz,
  sum(min(f.end_ts,w.end_ts)-max(f.ts,w.ts))/1e6 AS covered_ms
FROM launch_windows w JOIN frequency f ON f.ts<w.end_ts AND f.end_ts>w.ts
WHERE w.stage='first_started'
GROUP BY w.run_id,w.iteration,f.cpu ORDER BY w.ts,f.cpu;
