-- lockNew FOR UPDATE must visit FREE rows only (D5 / §2.4.2).
-- uk_therapist_slot / uk_bed_slot are (resource, date, slot_no) and would pin busy rows
-- if the planner used them for `slot_no IN (…) AND status='FREE'`.
ALTER TABLE therapist_slot
  ADD KEY idx_ts_free (therapist_id, slot_date, status, slot_no);

ALTER TABLE bed_slot
  ADD KEY idx_bs_free (bed_id, slot_date, status, slot_no);
