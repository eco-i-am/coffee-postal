-- Face check on the time clock (flag only, never blocks a punch).
-- A face "template" is 128 numbers computed on the tablet from a photo; the photo itself is not kept for enrollment.
-- It is biometric data: stored only with the employee's consent, removed when they leave or when a manager removes it.
ALTER TABLE employees
  ADD COLUMN face_template    REAL[],
  ADD COLUMN face_enrolled_at TIMESTAMPTZ,
  ADD COLUMN face_consent_by  BIGINT REFERENCES users(id);   -- the manager who recorded the employee's consent

-- What the check said about each punch. NO_FACE = the tablet could not see one clear face; NOT_CHECKED = the tablet could not run the check.
ALTER TABLE attendance_logs
  ADD COLUMN face_status VARCHAR(12) CHECK (face_status IN ('MATCH','REVIEW','NO_FACE','NOT_ENROLLED','NOT_CHECKED')),
  ADD COLUMN face_score  REAL;   -- distance to the enrolled face; lower is more alike
