# ERR-4013: TLS handshake retry with card acquirer

**Service:** payments
**Severity:** Low, if it recovers on retry.

## Symptoms
- payments logs `ERR-4013 TLS handshake retry` followed by `recovered on retry`.

## Cause
The card acquirer occasionally renegotiates ciphers. A single retry normally succeeds.

## Fix
No action needed when the log says `recovered on retry`. Escalate to the payments team only if
ERR-4013 appears more than 10 times in 5 minutes or without recovery.
