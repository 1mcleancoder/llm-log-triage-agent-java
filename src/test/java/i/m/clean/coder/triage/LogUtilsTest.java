package i.m.clean.coder.triage;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LogUtilsTest {

  @Test
  void checkIfTextIsPresentInAnyOfFiles() {
    List<String> lines =
        List.of(
            "gateway.log:1980: 2026-10-25T13:55:13.236Z INFO  [gateway] [gw-io-9] trace=60736a2b0b1c1828 POST /v2/charges -> card-acquirer pool=card-acquirer active=15/20 latency_ms=1473 status=200",
            "gateway.log:1983: 2026-10-25T13:55:16.114Z INFO  [gateway] [gw-io-5] trace=bb91ad7b906261ed POST /v2/charges -> card-acquirer pool=card-acquirer active=20/20 latency_ms=992 status=200",
            "payments.log:4561: 2026-10-25T14:02:16.542Z ERROR [payments] [pay-exec-27] trace=0ae8d8e65ea9df94 ERR-4012 Payment gateway timeout order_id=ord-1125289 upstream=gateway/card-acquirer elapsed_ms=5051 retry=1/2",
            "payments.log:4568: 2026-10-25T14:02:21.582Z ERROR [payments] [pay-exec-27] trace=0ae8d8e65ea9df94 ERR-4012 Payment gateway timeout order_id=ord-1125289 upstream=gateway/card-acquirer elapsed_ms=5061 retry=2/2 giving_up=true",
            "payments.log:4563: 2026-10-25T14:02:18.222Z ERROR [payments] [pay-exec-23] trace=9dc58aebc8403522 ERR-4012 Payment gateway timeout order_id=ord-5263604 upstream=gateway/card-acquirer elapsed_ms=5056 retry=1/2");

    final LogFiles logFiles = new LogFiles(Path.of("data/logs"));

    Assertions.assertTrue(LogUtils.checkIfTextIsPresentInSpecificLogFile(lines, logFiles));
  }

  //  @Test
  void checkIfTextIsPresentInAnyOfFiles2() {
    List<String> lines =
        List.of(
            "2026-10-25T13:55:07.967Z INFO  [gateway] [gw-io-12] trace=f8337833bcd70109 POST /v2/charges -> card-acquirer pool=card-acquirer active=10/20 latency_ms=1716 status=200",
            "2026-10-25T13:55:16.114Z INFO  [gateway] [gw-io-5] trace=bb91ad7b906261ed POST /v2/charges -> card-acquirer pool=card-acquirer active=20/20 latency_ms=992 status=200",
            "2026-10-25T14:02:11.499Z WARN  [gateway] [gw-io-14] trace=0ae8d8e65ea9df94 pool=card-acquirer active=20/20 waiting=50 acquire_timeout_ms=5000",
            "2026-10-25T14:02:16.539Z ERROR [gateway] [gw-io-14] trace=0ae8d8e65ea9df94 connection acquire timed out pool=card-acquirer waited_ms=5040",
            "2026-10-25T14:02:16.542Z ERROR [payments] [pay-exec-27] trace=0ae8d8e65ea9df94 ERR-4012 Payment gateway timeout order_id=ord-1125289 upstream=gateway/card-acquirer elapsed_ms=5051 retry=1/2",
            "2026-10-25T14:02:21.582Z ERROR [payments] [pay-exec-27] trace=0ae8d8e65ea9df94 ERR-4012 Payment gateway timeout order_id=ord-1125289 upstream=gateway/card-acquirer elapsed_ms=5061 retry=2/2 giving_up=true");

    final LogFiles logFiles = new LogFiles(Path.of("data/logs"));

    Assertions.assertTrue(LogUtils.checkIfTextIsPresentInSpecificLogFile(lines, logFiles));
  }
}
