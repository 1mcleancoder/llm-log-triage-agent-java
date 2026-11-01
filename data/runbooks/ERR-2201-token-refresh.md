# ERR-2201: Refresh token near expiry

**Service:** auth
**Severity:** Informational.

## Symptoms
- auth logs `ERR-2201 refresh token near expiry ... rotating (non-fatal)` at a steady rate all day.

## Cause
Mobile app clients rotate refresh tokens shortly before they expire. This is expected behaviour.

## Fix
No action needed. It is logged at WARN for historical reasons and is a frequent false lead
during incident triage.
