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
}
