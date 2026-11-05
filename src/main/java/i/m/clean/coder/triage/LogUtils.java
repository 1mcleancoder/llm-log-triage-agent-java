package i.m.clean.coder.triage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LogUtils {

  public static boolean checkIfTextIsPresentInAnyOfFiles(
      final List<String> lines, final LogFiles logFiles) {
    final List<String> allFiles = logFiles.listFiles();
    final Map<String, List<String>> linesByFileMap = new HashMap<>(allFiles.size());
    for (String line : lines) {
      boolean found = false;
      for (String file : allFiles) {
        String fileName = file.split(" ")[0];
        List<String> fileLines =
            linesByFileMap.computeIfAbsent(
                fileName,
                f ->
                    logFiles.readLines(f, 1, Integer.MAX_VALUE, Integer.MAX_VALUE).stream()
                        .map(l -> l.replaceFirst("^\\d+: ", ""))
                        .toList());
        if (fileLines.contains(line)) {
          found = true;
          break;
        }
      }
      if (!found) {
        return false;
      }
    }

    return true;
  }

  public static boolean checkIfTextIsPresentInSpecificLogFile(
      final List<String> lines, final LogFiles logFiles) {
    final List<String> allFiles = logFiles.listFiles();
    final Map<String, List<String>> linesByFileMap = new HashMap<>(allFiles.size());
    for (String line : lines) {
      final String[] lineSplits = line.split(":");
      final String logFileName = lineSplits[0];
      final int lineNumber = Integer.parseInt(lineSplits[1]);
      final String logLineWithLineNumber =
          logFiles.readLines(logFileName, lineNumber, lineNumber, 1).stream().findFirst().get();
      final String logLineForComparison = logFileName + ":" + logLineWithLineNumber;
      if (!logLineForComparison.equals(line)) {
        return false;
      }
    }

    return true;
  }
}
