package i.m.clean.coder.triage;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RunResultTest {

  private static RunResult sampleResult() {
    IncidentSummary summary =
        new IncidentSummary(
            IncidentSeverity.HIGH,
            "2026-10-25T14:02:11.499Z",
            List.of("gateway", "payments", "orders"),
            "Card-acquirer pool saturated due to traffic surge from campaign FLASH-OCT25",
            List.of(
                "2026-10-25T14:02:11.499Z WARN  [gateway] pool=card-acquirer active=20/20 waiting=50",
                "2026-10-25T14:02:16.542Z ERROR [payments] ERR-4012 Payment gateway timeout"),
            "Increase card-acquirer pool size and add rate limiting at the gateway.",
            List.of(
                "gateway POST /v2/charges per 5m: 13:50 ≈ 71 → 13:55 ≈ 281",
                "orders/5m: 13:50 ≈ 214 → 13:55 ≈ 842"));

    return new RunResult(true, EndedBy.end_turn, summary, 9, 256994, 0.0283904, 28215, List.of());
  }

  @Test
  void toPrettyString_containsAllFields() {
    String out = sampleResult().toPrettyString();

    Assertions.assertTrue(out.contains("answered     : true"));
    Assertions.assertTrue(out.contains("endedBy      : end_turn"));
    Assertions.assertTrue(out.contains("turns        : 9"));
    Assertions.assertTrue(out.contains("severity         : HIGH"));
    Assertions.assertTrue(out.contains("firstSeen        : 2026-10-25T14:02:11.499Z"));
    Assertions.assertTrue(out.contains("    - gateway"));
    Assertions.assertTrue(out.contains("    - payments"));
    Assertions.assertTrue(out.contains("rootCauseGuess"));
    Assertions.assertTrue(out.contains("    - 2026-10-25T14:02:11.499Z WARN"));
    Assertions.assertTrue(out.contains("    - gateway POST /v2/charges"));
  }

  @Test
  void toPrettyString_nullSummary() {
    RunResult result = new RunResult(false, EndedBy.budget, null, 3, 50000, 0.005, 5000, List.of());
    String out = result.toPrettyString();

    Assertions.assertTrue(out.contains("summary      : null"));
  }

  @Test
  void logPretty_doesNotThrow() {
    // just verifies no exception is thrown when logging
    Assertions.assertDoesNotThrow(() -> sampleResult().logPretty());
  }

  // Real log lines as they appear in the log files (no file:line prefix)
  private static final Set<String> REAL_LOG_LINES =
      Set.of(
          "2026-10-25T13:54:20.000Z INFO  [orders] [scheduler] trace=- campaign FLASH-OCT25 activated: 30% off sitewide, push notification sent to 1.2M users",
          "2026-10-25T13:55:16.114Z INFO  [gateway] [gw-io-5] trace=bb91ad7b906261ed POST /v2/charges -> card-acquirer pool=card-acquirer active=20/20 latency_ms=992 status=200",
          "2026-10-25T14:02:11.499Z WARN  [gateway] [gw-io-14] trace=0ae8d8e65ea9df94 pool=card-acquirer active=20/20 waiting=50 acquire_timeout_ms=5000",
          "2026-10-25T14:02:16.539Z ERROR [gateway] [gw-io-14] trace=0ae8d8e65ea9df94 connection acquire timed out pool=card-acquirer waited_ms=5040",
          "2026-10-25T14:02:16.542Z ERROR [payments] [pay-exec-27] trace=0ae8d8e65ea9df94 ERR-4012 Payment gateway timeout order_id=ord-1125289 upstream=gateway/card-acquirer elapsed_ms=5051 retry=1/2",
          "2026-10-25T14:02:21.582Z ERROR [payments] [pay-exec-27] trace=0ae8d8e65ea9df94 ERR-4012 Payment gateway timeout order_id=ord-1125289 upstream=gateway/card-acquirer elapsed_ms=5061 retry=2/2 giving_up=true",
          "2026-10-25T14:02:21.642Z ERROR [orders] [http-nio-8080-exec-8] trace=0ae8d8e65ea9df94 payment failed order_id=ord-1125289 status=502 cause=\"payments: ERR-4012\" -> order marked PAYMENT_FAILED",
          "2026-10-25T14:40:05.000Z INFO  [payments] [ops] trace=- on-call note: errors subsided as campaign traffic decreased; pool size unchanged");

  // Real log lines as they appear in the log files (no file:line prefix)
  private static final Set<String> REAL_LOG_LINES_2 =
      Set.of(
          "2026-10-25T13:55:05.351Z INFO [gateway] [gw-io-13] trace=1b560a1baf4b3ef3 POST /v2/charges -> card-acquirer pool=card-acquirer active=9/20 latency_ms=809 status=200",
          "2026-10-25T13:55:16.114Z INFO [gateway] [gw-io-5] trace=bb91ad7b906261ed POST /v2/charges -> card-acquirer pool=card-acquirer active=20/20 latency_ms=992 status=200",
          "2026-10-25T14:02:16.539Z ERROR [gateway] [gw-io-14] trace=0ae8d8e65ea9df94 connection acquire timed out pool=card-acquirer waited_ms=5040",
          "2026-10-25T14:02:16.542Z ERROR [payments] [pay-exec-27] trace=0ae8d8e65ea9df94 ERR-4012 Payment gateway timeout order_id=ord-1125289 upstream=gateway/card-acquirer elapsed_ms=5051 retry=1/2",
          "2026-10-25T14:02:21.582Z ERROR [payments] [pay-exec-27] trace=0ae8d8e65ea9df94 ERR-4012 Payment gateway timeout order_id=ord-1125289 upstream=gateway/card-acquirer elapsed_ms=5061 retry=2/2 giving_up=true",
          "2026-10-25T14:02:21.642Z ERROR [orders] [http-nio-8080-exec-8] trace=0ae8d8e65ea9df94 payment failed order_id=ord-1125289 status=502 cause=\"payments: ERR-4012\" -> order marked PAYMENT_FAILED");

  @Test
  void fabricatedEvidence_allReal_returnsEmpty() {
    // Evidence lines without file:line prefix — all present in the real log lines
    IncidentSummary summary =
        new IncidentSummary(
            IncidentSeverity.HIGH,
            "2026-10-25T14:02:16.542Z",
            List.of("payments", "gateway", "orders"),
            "FLASH-OCT25 campaign saturated card-acquirer pool",
            List.of(
                "2026-10-25T13:54:20.000Z INFO  [orders] [scheduler] trace=- campaign FLASH-OCT25 activated: 30% off sitewide, push notification sent to 1.2M users",
                "2026-10-25T14:02:16.539Z ERROR [gateway] [gw-io-14] trace=0ae8d8e65ea9df94 connection acquire timed out pool=card-acquirer waited_ms=5040",
                "2026-10-25T14:02:16.542Z ERROR [payments] [pay-exec-27] trace=0ae8d8e65ea9df94 ERR-4012 Payment gateway timeout order_id=ord-1125289 upstream=gateway/card-acquirer elapsed_ms=5051 retry=1/2",
                "2026-10-25T14:40:05.000Z INFO  [payments] [ops] trace=- on-call note: errors subsided as campaign traffic decreased; pool size unchanged"),
            "Raise pool size",
            List.of());

    RunResult result =
        new RunResult(true, EndedBy.end_turn, summary, 8, 180967, 0.021, 30970, List.of());

    Assertions.assertTrue(result.fabricatedEvidence(REAL_LOG_LINES).isEmpty());
  }

  @Test
  void fabricatedEvidence_withFilePrefixes_stripsAndMatches() {
    // Evidence lines with file:line prefix as Claude sometimes returns them
    IncidentSummary summary =
        new IncidentSummary(
            IncidentSeverity.HIGH,
            "2026-10-25T14:02:16.542Z",
            List.of("payments", "gateway"),
            "Pool saturation",
            List.of(
                "orders.log:5413: 2026-10-25T13:54:20.000Z INFO  [orders] [scheduler] trace=- campaign FLASH-OCT25 activated: 30% off sitewide, push notification sent to 1.2M users",
                "gateway.log:2400: 2026-10-25T14:02:11.499Z WARN  [gateway] [gw-io-14] trace=0ae8d8e65ea9df94 pool=card-acquirer active=20/20 waiting=50 acquire_timeout_ms=5000",
                "payments.log:4561: 2026-10-25T14:02:16.542Z ERROR [payments] [pay-exec-27] trace=0ae8d8e65ea9df94 ERR-4012 Payment gateway timeout order_id=ord-1125289 upstream=gateway/card-acquirer elapsed_ms=5051 retry=1/2"),
            "Raise pool size",
            List.of());

    RunResult result =
        new RunResult(true, EndedBy.end_turn, summary, 7, 144119, 0.017, 28779, List.of());

    Assertions.assertTrue(result.fabricatedEvidence(REAL_LOG_LINES).isEmpty());
  }

  @Test
  void fabricatedEvidence_someFabricated_returnsOnlyFabricated() {
    String fabricated =
        "2026-10-25T14:00:00.000Z ERROR [payments] ERR-9999 invented error that never happened";
    IncidentSummary summary =
        new IncidentSummary(
            IncidentSeverity.HIGH,
            "2026-10-25T14:02:16.542Z",
            List.of("payments"),
            "Some guess",
            List.of(
                "2026-10-25T14:02:16.539Z ERROR [gateway] [gw-io-14] trace=0ae8d8e65ea9df94 connection acquire timed out pool=card-acquirer waited_ms=5040",
                fabricated),
            "Fix it",
            List.of());

    RunResult result =
        new RunResult(true, EndedBy.end_turn, summary, 5, 100000, 0.012, 20000, List.of());

    List<String> fabricatedEvidence = result.fabricatedEvidence(REAL_LOG_LINES);
    Assertions.assertEquals(1, fabricatedEvidence.size());
    Assertions.assertEquals(fabricated, fabricatedEvidence.get(0));
  }

  @Test
  void fabricatedEvidence_nullSummary_returnsEmpty1() {
    RunResult result = new RunResult(false, EndedBy.budget, null, 3, 50000, 0.005, 5000, List.of());
    Assertions.assertTrue(result.fabricatedEvidence(REAL_LOG_LINES).isEmpty());
  }

  @Test
  void fabricatedEvidence_nullSummary_returnsEmpty2() {
    RunResult result = new RunResult(false, EndedBy.budget, null, 3, 50000, 0.005, 5000, List.of());
    Assertions.assertTrue(result.fabricatedEvidence(REAL_LOG_LINES_2).isEmpty());
  }
}
