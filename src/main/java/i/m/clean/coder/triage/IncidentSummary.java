package i.m.clean.coder.triage;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

record IncidentSummary(
        @JsonPropertyDescription("Overall impact")
        IncidentSeverity severity,
        @JsonPropertyDescription("Timestamp of the first line where this error or ref to the request is first encountered")
        @JsonProperty("first_seen")
        @Nullable
        String firstSeen,
        @JsonPropertyDescription("all the impacted service")
        @JsonProperty("affected_services")
        List<String> affectedServices,
        @JsonPropertyDescription("what might have caused it")
        @JsonProperty("root_cause_guess")
        String rootCauseGuess,
        @JsonPropertyDescription("Actual log lines containing the error")
        List<String> evidence,
        @JsonPropertyDescription("possible actions to fix the situation")
        @JsonProperty("suggested_action")
        String suggestedAction) {
}