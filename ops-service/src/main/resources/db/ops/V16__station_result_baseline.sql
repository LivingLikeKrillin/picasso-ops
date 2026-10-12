-- Latest ops.work_result version of the run's receipt when the run (re)entered RUNNING. Only newer results speak for this attempt.
ALTER TABLE ops.station_run ADD COLUMN result_baseline INTEGER CHECK(result_baseline >= 0);
-- Set with the baseline: true when RUNNING was re-entered by resuming observation of the same execution, so the baseline only screens review states.
ALTER TABLE ops.station_run ADD COLUMN baseline_review_only BOOLEAN NOT NULL DEFAULT false;
