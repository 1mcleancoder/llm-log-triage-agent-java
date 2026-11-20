package i.m.clean.coder.triage;

import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ToolsUtilsTest {

  @Test
  void singleFile_singleHit() {
    List<String> hits = List.of("payments.log:42: some log line");
    Assertions.assertEquals("payments.log 1", ToolUtils.getSearchEntriesSummary(hits));
  }

  @Test
  void singleFile_multipleHits() {
    List<String> hits =
        List.of(
            "payments.log:1: line one", "payments.log:2: line two", "payments.log:3: line three");
    Assertions.assertEquals("payments.log 3", ToolUtils.getSearchEntriesSummary(hits));
  }

  @Test
  void multipleFiles() {
    List<String> hits =
        List.of("payments.log:1: line", "payments.log:2: line", "gateway.log:5: line");
    String result = ToolUtils.getSearchEntriesSummary(hits);
    // order is not guaranteed (HashMap), so assert both parts are present
    Assertions.assertTrue(
        result.contains("payments.log 2"), "expected payments.log 2 in: " + result);
    Assertions.assertTrue(result.contains("gateway.log 1"), "expected gateway.log 1 in: " + result);
  }

  @Test
  void emptyList() {
    Assertions.assertEquals("", ToolUtils.getSearchEntriesSummary(List.of()));
  }

  @Test
  void hitWithColonInLogLine() {
    // filename ends at first colon; colons in the log body should not affect the file name
    List<String> hits = List.of("auth.log:99: ERROR host=db:5432 connection refused");
    Assertions.assertEquals("auth.log 1", ToolUtils.getSearchEntriesSummary(hits));
  }
}
