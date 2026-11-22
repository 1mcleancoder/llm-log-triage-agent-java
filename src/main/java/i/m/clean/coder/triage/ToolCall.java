package i.m.clean.coder.triage;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam;

public record ToolCall(String id, String name, String args, String result, boolean isError) {
  public ContentBlockParam toBlock(boolean cleared) {
    var b =
        ToolResultBlockParam.builder()
            .toolUseId(id)
            .content(
                cleared ? "[cleared: %s(%s). Re-run if needed.]".formatted(name, args) : result);
    if (isError) b.isError(true); // only when true:
    return ContentBlockParam.ofToolResult(b.build());
  }
}
