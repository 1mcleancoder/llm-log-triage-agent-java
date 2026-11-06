package i.m.clean.coder.triage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

  /**
   * Produces the output like "13:50 42 | 13:51 39 | 13:52 44 | 13:53 41 | 13:54 118 | 13:55 171 …"
   *
   * @param regex to match
   * @param file if not specified then it searches all the files
   * @param fromTs the log file line ISO timestamp to start search from
   * @param toTs the log file line ISO timestamp to end search
   * @param bktInterval can have only 3 values: 30s, 1m, 5m
   * @return a concatenated string with | as separator, containing timestamp at bucket interval with
   *     the count of regex matches
   */
  public String countMatches(
      String regex, String file, String fromTs, String toTs, String bktInterval) {
    long bktSize = getBucketIntervalMillis(bktInterval);
    long from = Instant.parse(fromTs).toEpochMilli();
    long to = Instant.parse(toTs).toEpochMilli();

    final long fromLb = from - (from % bktSize); // lower bound as per bucket bktSize
    final long toUp =
        to % bktSize == 0 ? to : to - (to % bktSize) + bktSize; // upper bound as per bucket bktSize

    long numBuckets = (to - from) / bktSize;
    if (numBuckets > 240) {
      throw new IllegalArgumentException("too many buckets: %s".formatted(numBuckets));
    }

    Map<Long, Integer> countsByBktSize =
        computeCountsByBucketSize(regex, file, fromLb, toUp, bktSize);
    return combineTimestamps(bktSize, countsByBktSize);
  }

  private static String combineTimestamps(long bktSize, Map<Long, Integer> countsByBktSize) {
    String FORMAT = (bktSize == 300000L) ? "HH:mm" : "HH:mm:ss";

    StringBuilder builder = new StringBuilder(countsByBktSize.size() * 10);
    int count = 0;
    for (Map.Entry<Long, Integer> entry : countsByBktSize.entrySet()) {
      ZonedDateTime zonedDateTime = Instant.ofEpochMilli(entry.getKey()).atZone(ZoneOffset.UTC);
      String fmtdTime = zonedDateTime.format(DateTimeFormatter.ofPattern(FORMAT));

      builder.append(fmtdTime).append(" ").append(entry.getValue());
      ++count;

      if (count < countsByBktSize.size()) {
        builder.append(" | ");
      }
    }
    return builder.toString();
  }

  private Map<Long, Integer> computeCountsByBucketSize(
      String regex, String file, long from, long to, long size) {
    Map<Long, Integer> countsByTsSize = new TreeMap<>();
    // initialize the bucket
    for (long b = from; b < to; b += size) {
      countsByTsSize.put(b, 0);
    }

    Pattern p = Pattern.compile(regex);
    for (Path f : targets(file)) {
      List<String> lines = read(f);
      for (String line : lines) {
        if (p.matcher(line).find()) {
          String ts = line.length() >= 24 ? line.substring(0, 24) : "";
          if (ts.isEmpty()) {
            continue;
          }
          long time = Instant.parse(ts).toEpochMilli();
          if (time >= from && time <= to) {
            long timeBkt = time - (time % size);
            countsByTsSize.merge(timeBkt, 1, Integer::sum);
          }
        }
      }
    }
    return countsByTsSize;
  }

  private static long getBucketIntervalMillis(String bktInterval) {
    return switch (bktInterval) {
      case "30s" -> 30 * 1000;
      case "1m" -> 60 * 1000;
      case "5m" -> 5 * 60 * 1000;
      default ->
          throw new IllegalArgumentException("Invalid bucket interval: %s".formatted(bktInterval));
    };
  }
}
