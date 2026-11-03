package i.m.clean.coder.triage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum IncidentSeverity {
    HIGH("high"), MEDIUM("medium"), LOW("low"), UNKNOWN("unknown");

    @Getter
    @JsonValue
    private final String value;

    IncidentSeverity(String value) {
        this.value = value;
    }

    @JsonCreator
    public static IncidentSeverity fromValue(String value) {
        for (IncidentSeverity severity : values()) {
            if (severity.value.equalsIgnoreCase(value)) {
                return severity;
            }
        }
        throw new IllegalArgumentException("Unknown severity: " + value);
    }
}
