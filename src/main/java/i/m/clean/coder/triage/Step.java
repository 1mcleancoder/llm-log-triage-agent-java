package i.m.clean.coder.triage;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import java.util.ArrayList;
import java.util.List;

public record Step(int turn, MessageParam assistant, List<ToolCall> calls, String note) {
  MessageParam toolResultsMsg(boolean cleared) {
    List<ContentBlockParam> blocks = new ArrayList<>();
    for (ToolCall c : calls) blocks.add(c.toBlock(cleared));
    if (note != null) blocks.add(ContentBlockParam.ofText(note));
    return MessageParam.ofUserOfContentBlockParams(blocks);
  }
}
