package i.m.clean.coder.triage;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import java.util.List;
import java.util.Map;

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

  public static Tool createSearchLogsTool() {
    return Tool.builder()
        .name("search_logs")
        .description(
            """
            Search log files for lines matching a Java regex. Use this to find errors,
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
}
