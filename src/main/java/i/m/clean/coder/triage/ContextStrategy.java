package i.m.clean.coder.triage;

import com.anthropic.models.messages.MessageParam;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

interface ContextStrategy {
  List<MessageParam> build(MessageParam firstUser, List<Step> history, int currentTurn);

  class KeepAll implements ContextStrategy {
    public List<MessageParam> build(MessageParam firstUser, List<Step> history, int currentTurn) {
      List<MessageParam> out = new ArrayList<>();
      out.add(firstUser);
      for (Step s : history) {
        out.add(s.assistant()); // unchanged
        out.add(s.toolResultsMsg(false)); // unchanged
      }
      return out;
    }
  }

  @Slf4j
  class ClearOld implements ContextStrategy {
    private final int keepLast;

    ClearOld(int keepLast) {
      this.keepLast = keepLast;
    }

    @Override
    public List<MessageParam> build(MessageParam firstUser, List<Step> history, int currentTurn) {
      final List<MessageParam> out = new ArrayList<>();
      out.add(firstUser);

      int clearedCalls = 0;
      long clearedChars = 0;
      for (Step s : history) {
        final boolean clearOpt = (currentTurn - s.turn() > keepLast);
        out.add(s.assistant());
        out.add(s.toolResultsMsg(clearOpt));

        // telemetry
        if (clearOpt) {
          clearedCalls += s.calls().size();
          for (ToolCall c : s.calls()) clearedChars += c.result().length();
        }
      }

      log.atDebug().log(
          "turn {} cleared {} results, ~{} chars", currentTurn, clearedCalls, clearedChars);
      return out;
    }
  }
}
