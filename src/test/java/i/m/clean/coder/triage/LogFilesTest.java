package i.m.clean.coder.triage;

import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogFilesTest {
  LogFiles logFiles;

  @BeforeEach
  void setup() {
    logFiles = new LogFiles(Path.of("data/logs"));
  }

  @Test
  void countMatches() {
    String matches =
        logFiles.countMatches(
            "POST", "orders.log", "2026-10-25T12:05:06.444Z", "2026-10-25T12:08:28.846Z", "30s");
    String expected =
        "12:05:00 9 | 12:05:30 16 | 12:06:00 8 | 12:06:30 8 | 12:07:00 8 | 12:07:30 5 | 12:08:00 9";
    Assertions.assertEquals(expected, matches);
  }

  @Test
  void extractLogLine_withPrefix() {
    String hit = "payments.log:42: 2026-10-25T14:02:16Z ERROR payment failed";
    Assertions.assertEquals(
        "2026-10-25T14:02:16Z ERROR payment failed", LogFiles.extractLogLine(hit));
  }

  @Test
  void extractLogLine_plainLogLine() {
    String plain = "2026-10-25T14:02:16Z ERROR payment failed";
    Assertions.assertEquals(plain, LogFiles.extractLogLine(plain));
  }

  @Test
  void extractLogLine_timestampColonsNotConfused() {
    // colons inside the log body (host:port) must not be mistaken for the prefix
    String hit = "auth.log:99: ERROR host=db:5432 connection refused";
    Assertions.assertEquals("ERROR host=db:5432 connection refused", LogFiles.extractLogLine(hit));
  }

  @Test
  void extractLogLine_hyphenatedFilename() {
    String hit = "card-acquirer.log:7: INFO request received";
    Assertions.assertEquals("INFO request received", LogFiles.extractLogLine(hit));
  }
}
