package i.m.clean.coder.triage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Plain-Java helpers for reading the sample logs. No AI here.
 *
 * <p>Stage 1 uses {@link #between} to cut an excerpt. In stage 3 you will expose {@link
 * #listFiles}, {@link #search} and {@link #readLines} to Claude as tools. Every method caps its
 * output: a tool that can return 40,000 lines will blow up your context window (and your bill).
 */
public final class LogFiles {

  private final Path dir;

  public LogFiles(Path dir) {
    this.dir = dir;
  }

  /** File names with sizes, e.g. "payments.log (12728 lines)". */
  public List<String> listFiles() {
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(p -> p.toString().endsWith(".log"))
          .sorted()
          .map(p -> p.getFileName() + " (" + countLines(p) + " lines)")
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Lines matching a Java regex, prefixed with "file:lineNumber: ". Searches one file, or every
   * .log file when {@code file} is null.
   */
  public List<String> search(String regex, String file, int maxResults) {
    Pattern p = Pattern.compile(regex);
    List<String> out = new ArrayList<>();
    for (Path f : targets(file)) {
      List<String> lines = read(f);
      for (int i = 0; i < lines.size() && out.size() < maxResults; i++) {
        if (p.matcher(lines.get(i)).find()) {
          out.add(f.getFileName() + ":" + (i + 1) + ": " + lines.get(i));
        }
      }
    }
    return out;
  }

  /** Lines {@code from}..{@code to} (1-based, inclusive), at most {@code maxLines}. */
  public List<String> readLines(String file, int from, int to, int maxLines) {
    List<String> lines = read(resolve(file));
    int start = Math.max(1, from);
    int end = Math.min(lines.size(), Math.min(to, start + maxLines - 1));
    List<String> out = new ArrayList<>();
    for (int i = start; i <= end; i++) {
      out.add(i + ": " + lines.get(i - 1));
    }
    return out;
  }

  /**
   * Lines whose ISO timestamp falls in [fromTs, toTs), at most {@code maxLines}. Timestamps compare
   * as strings, e.g. "2026-10-25T14:01:30".
   */
  public List<String> between(String file, String fromTs, String toTs, int maxLines) {
    List<String> out = new ArrayList<>();
    for (String line : read(resolve(file))) {
      String ts = line.length() >= 19 ? line.substring(0, 19) : "";
      if (ts.compareTo(fromTs) >= 0 && ts.compareTo(toTs) < 0) {
        out.add(line);
        if (out.size() >= maxLines) {
          break;
        }
      }
    }
    return out;
  }

  private List<Path> targets(String file) {
    if (file != null && !file.isBlank()) {
      return List.of(resolve(file));
    }
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(p -> p.toString().endsWith(".log")).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Refuses paths outside the log folder (a tool must never read arbitrary files). */
  private Path resolve(String file) {
    Path p = dir.resolve(file).normalize();
    if (!p.startsWith(dir.normalize()) || !Files.isRegularFile(p)) {
      throw new IllegalArgumentException("Unknown log file: " + file);
    }
    return p;
  }

  private static List<String> read(Path p) {
    try {
      return Files.readAllLines(p, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static long countLines(Path p) {
    try (Stream<String> s = Files.lines(p, StandardCharsets.UTF_8)) {
      return s.count();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
