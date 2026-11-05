package i.m.clean.coder.triage;

import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;

/**
 * Quick check that the helpers work, with no API key needed: mvn -q compile exec:java
 * -Dstage=com.example.triage.LogFilesSmokeTest
 */
@Slf4j
public final class LogFilesSmokeTest {

  private LogFilesSmokeTest() {}

  public static void main(String[] args) {
    LogFiles logs = new LogFiles(Path.of("data/logs"));
    logs.listFiles().forEach(System.out::println);
    log.info("--- first 3 ERR-4012 lines ---");
    logs.search("ERR-4012", "payments.log", 3).forEach(System.out::println);
    log.info("--- excerpt size 14:02:00-14:03:00 ---");
    log.info(
        "{} lines",
        logs.between("payments.log", "2026-10-25T14:02:00", "2026-10-25T14:03:00", 80).size());
    try {
      logs.readLines("../../pom.xml", 1, 5, 5);
      log.info("FAIL: path traversal was allowed");
    } catch (IllegalArgumentException e) {
      log.info("OK: {}", e.getMessage());
    }
  }
}
