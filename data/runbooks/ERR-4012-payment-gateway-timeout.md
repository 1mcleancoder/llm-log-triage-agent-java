# ERR-4012: Payment gateway timeout

**Service:** payments (upstream: gateway, pool `card-acquirer`)
**Severity:** High. Customers cannot complete checkout.

## Symptoms
- payments logs `ERR-4012 Payment gateway timeout` with `elapsed_ms` around 5000.
- gateway logs `connection acquire timed out pool=card-acquirer`.
- gateway logs show `active=20/20` with a growing `waiting` count.
- orders logs `payment failed ... status=502` and marks orders `PAYMENT_FAILED`.

## Cause
The gateway's connection pool to the card acquirer is exhausted. Every connection is busy,
new charge requests wait for `acquire_timeout_ms` (5000 ms) and then fail. This usually
happens during a traffic spike, such as a marketing campaign.

## Fix
1. Confirm the pool is saturated: look for `active=20/20` in gateway logs.
2. Increase `gateway.pool.card-acquirer.max` from 20 to 50 in the gateway config.
3. Restart the gateway service, one instance at a time.
4. Watch for `waiting=0` in gateway logs and confirm ERR-4012 stops.

**Do not restart the payments service.** It does not fix pool exhaustion and drops in-flight charges.

## Prevention
- Ask Marketing to announce large campaigns 24 hours ahead so the pool can be pre-scaled.
- Alert when `waiting > 0` for more than 60 seconds.
