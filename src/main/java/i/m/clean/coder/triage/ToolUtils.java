package i.m.clean.coder.triage;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ToolUtils {
  public static Tool createListFilesTool() {
    return Tool.builder()
        .name("list_files")
        .description(
            """
            Provides a list of log file names along with sizes in brackets.
            Use this to find the list of all files present. Returns upto 10 file names each with the format
            e.g. payments.log (12728 lines)
            """)
        .inputSchema(Tool.InputSchema.builder().build())
        .build();
  }

  @SuppressWarnings("unchecked")
  public static Tool createSearchLogsTool() {
    return Tool.builder()
        .name("search_logs")
        .description(
            """
            Search log files for lines matching a Java regex with case insensitive. Use this to find errors,
            error codes, or all lines for one trace id. Returns up to 50 matching lines
            as 'file:line: text'. Omit 'file' to search all four logs.
            """)
        .inputSchema(
            Tool.InputSchema.builder()
                .properties(
                    JsonValue.from(
                        Map.of(
                            "regex",
                            Map.of(
                                "type",
                                "string",
                                "description",
                                "Java regex, e.g. ERR-4012 or trace=91f10859aa837319"),
                            "file",
                            Map.of(
                                "type",
                                "string",
                                "description",
                                "Optional: payments.log, gateway.log, orders.log or auth.log"),
                            "maxResults",
                            Map.of(
                                "type",
                                "integer",
                                "description",
                                "the max number of matching lines to be returned, eg. 3 means it will return max 3 matching lines even there are 10 matching lines found"))))
                .required(List.of("regex", "maxResults"))
                .build())
        .build();
  }

  @SuppressWarnings("unchecked")
  public static Tool createReadLinesTool() {
    return Tool.builder()
        .name("read_lines")
        .description(
            """
            Read context around a hit. Use this to read lines from a file starting from a line number to a specific line number with at-most max lines.
            e.g.. when from=2, to=17, maxLines=5, then it will return 5 lines starting with line 2 and ending with number 6.
            """)
        .inputSchema(
            Tool.InputSchema.builder()
                .properties(
                    JsonValue.from(
                        Map.of(
                            "file",
                            Map.of(
                                "type",
                                "string",
                                "description",
                                "the name of the file, e.g. payments.log"),
                            "from",
                            Map.of(
                                "type",
                                "integer",
                                "description",
                                "starting line number 1-based, inclusive e.g. 2"),
                            "to",
                            Map.of(
                                "type",
                                "integer",
                                "description",
                                "ending line number 1-based, inclusive e.g. 10"),
                            "maxLines",
                            Map.of("type", "integer", "description", "at-most max lines e.g. 15"))))
                .required(List.of("file", "from", "to", "maxLines"))
                .build())
        .build();
  }

  @SuppressWarnings("unchecked")
  public static Tool createCountMatchesTool() {
    return Tool.builder()
        .name("count_matches")
        .description(
            """
            Count the regex matches for the specific time window. Use this to find out the count of regex matching between from and to time intervals with the specified bucket size.
            tip: search_logs first to find the exact line format, then count it.
            e.g. with regex pattern in log file="orders.log", fromTs="2026-10-25T12:05:06.444Z", toTs="2026-10-25T12:08:28.846Z", bktInternal="30s", when it returns the string
            "12:05:00 9 | 12:05:30 16 | 12:06:00 8 | 12:06:30 8 | 12:07:00 8 | 12:07:30 5 | 12:08:00 9" where there are 7 strings concatenated with | chars, each string represents two values, the lower bound timestamp
            starting with the 12:05:00 which is the lowerbound fromTs and 9 which represents the regex count of lines between this time and this time plus the next bucket interval time.
            Also the number of time intervals should not exceed 240 say bktInterval=1m, fromTs="2026-10-25T12:00:01,456Z", toTs="2026-10-25T16:01:04.846Z", this is invalid input since number of intervals is 242
            which is greater than 240.
            """)
        .inputSchema(
            Tool.InputSchema.builder()
                .properties(
                    JsonValue.from(
                        Map.of(
                            "regex",
                            Map.of(
                                "type",
                                "string",
                                "description",
                                "Java regex, e.g. ERR-4012 or trace=91f10859aa837319"),
                            "file",
                            Map.of(
                                "type",
                                "string",
                                "description",
                                "Optional: payments.log, gateway.log, orders.log or auth.log"),
                            "fromTs",
                            Map.of(
                                "type",
                                "string",
                                "description",
                                "Starting timestamp representing a timestamp of a specific log line e.g. 2026-10-25T12:08:28.846Z"),
                            "toTs",
                            Map.of(
                                "type",
                                "string",
                                "description",
                                "Ending timestamp representing a timestamp of a specific log line e.g. 2026-10-25T12:16:18.456Z"),
                            "bktInterval",
                            Map.of(
                                "type",
                                "string",
                                "description",
                                "Bucket interval, can have 3 values: 30s, 1m and 5m. Here 30s represents 30 seconds etc."))))
                .required(List.of("regex", "fromTs", "toTs", "bktInterval"))
                .build())
        .build();
  }

  public static String runTool(ToolUseBlock tool, final LogFiles logFiles) {
    switch (tool.name()) {
      case "list_files":
        log.atDebug().log("[tool_use: name={}]", tool.name());
        return String.join(",", logFiles.listFiles());

      case "search_logs":
        record SearchArgs(String regex, String file, int maxResults) {}
        final SearchArgs args = tool._input().convert(SearchArgs.class);
        //        int cap = Math.min(args.maxResults, 50);
          assert args != null;
          List<String> hits = logFiles.search(args.regex(), args.file(), args.maxResults);
        boolean truncated = hits.size() > args.maxResults;
        String body =
            String.join("\n", hits.subList(0, Math.min(args.maxResults + 1, hits.size())));
        log.atDebug().log(
            "[tool_use: name={}, SearchArgs={}, truncated={}]", tool.name(), args, truncated);
        return truncated
            ? body
                + "\n[truncated: "
                + hits.size()
                + " matches - "
                + getSearchEntriesSummary(hits)
                + "; narrow the regex or pick a file]"
            : body;

      case "read_lines":
        record ReadLineArgs(String file, int from, int to, int maxLines) {}
        final ReadLineArgs rlArgs = tool._input().convert(ReadLineArgs.class);
          assert rlArgs != null;
          List<String> readLines =
            logFiles.readLines(rlArgs.file, rlArgs.from, rlArgs.to, rlArgs.maxLines);
        log.atDebug().log(
            "[tool_use: name={}, args={}, matchingLines.size()={}]",
            tool.name(),
            rlArgs,
            readLines.size());
        return String.join("\n", readLines);

      case "count_matches":
        record CountArgs(String regex, String file, String from, String to, String bktInterval) {}
        final CountArgs cArgs = tool._input().convert(CountArgs.class);
          assert cArgs != null;
          String matches =
            logFiles.countMatches(cArgs.regex, cArgs.file, cArgs.from, cArgs.to, cArgs.bktInterval);
        log.atDebug().log(
            "[tool_use: name={}, args={}, matchingLines.size()={}]",
            tool.name(),
            cArgs,
            matches.length() > 20 ? matches.substring(0, 20) + " ... " : matches);
        return matches;
    }
    throw new IllegalArgumentException("Unrecognized tool: %s".formatted(tool.name()));
  }

  public static String getSearchEntriesSummary(List<String> hits) {
    Map<String, Integer> countByFileName = new HashMap<>();
    for (String hit : hits) {
      String fileName = hit.substring(0, hit.indexOf(':'));
      countByFileName.merge(fileName, 1, Integer::sum);
    }

    StringBuilder summary = new StringBuilder();
    int i = 0;
    for (Map.Entry<String, Integer> entry : countByFileName.entrySet()) {
      summary.append(entry.getKey()).append(" ").append(entry.getValue());
      if (i != countByFileName.size() - 1) {
        summary.append(", ");
      }
    }

    return summary.toString();
  }
}
