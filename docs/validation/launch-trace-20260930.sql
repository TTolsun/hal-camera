-- Dataset-specific evidence: run-1.perfetto-trace, SHA-256
-- 1ef7d36efd05ecdcca44453aa8a42b6435708c2637a20245683739653fd59fa5
-- BOOTTIME, report 20260930-001255-599 iteration 1.
INCLUDE PERFETTO MODULE slices.with_context;
SELECT id,ts,dur,ts+dur AS end_ts,name,process_name,thread_name,utid
FROM thread_or_process_slice
WHERE id IN (11651,11844,12751,13300,13305,13310)
ORDER BY ts;

SELECT state,
  sum(min(ts+dur,54692029720664)-max(ts,54691889944882))/1e6 AS duration_ms
FROM thread_state
WHERE utid=1799 AND dur>0 AND ts<54692029720664 AND ts+dur>54691889944882
GROUP BY state;

-- Directly verify the nonoverlapping boundary decomposition; units are ns.
WITH boundaries(label,ts,end_ts) AS (VALUES
 ('app_to_service',54691883270612,54691884636132),
 ('service_to_reconfigure',54691884636132,54691885323320),
 ('reconfigure',54691885323320,54692030475351),
 ('reconfigure_to_first_request',54692030475351,54692030969726),
 ('first_request',54692030969726,54692034896549),
 ('request_return_to_vendor_notify',54692034896549,54692133196601),
 ('vendor_notify_to_app_binder',54692133196601,54692133367851),
 ('app_binder_to_report_mark',54692133367851,54692133570924)
)
SELECT label,ts,end_ts,(end_ts-ts)/1e6 AS duration_ms FROM boundaries ORDER BY ts;
