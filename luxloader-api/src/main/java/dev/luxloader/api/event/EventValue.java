package dev.luxloader.api.event;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deeply immutable scalar, array, or object value used by event payloads. */
public sealed interface EventValue permits EventValue.Text, EventValue.IntegerNumber,
        EventValue.DecimalNumber, EventValue.Flag, EventValue.Array, EventValue.ObjectValue {

    record Text(String value) implements EventValue {
        public Text { value = Objects.requireNonNull(value, "value"); }
    }

    record IntegerNumber(long value) implements EventValue { }

    record DecimalNumber(double value) implements EventValue {
        public DecimalNumber {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("value must be finite");
        }
    }

    record Flag(boolean value) implements EventValue { }

    record Array(List<EventValue> values) implements EventValue {
        public Array {
            Objects.requireNonNull(values, "values");
            ArrayList<EventValue> copy = new ArrayList<>(values.size());
            for (EventValue value : values) copy.add(Objects.requireNonNull(value, "array value"));
            values = List.copyOf(copy);
        }
    }

    record ObjectValue(Map<String, EventValue> values) implements EventValue {
        public ObjectValue {
            Objects.requireNonNull(values, "values");
            LinkedHashMap<String, EventValue> copy = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                if (key == null || key.isBlank()) throw new IllegalArgumentException("event field keys must not be blank");
                copy.put(key, Objects.requireNonNull(value, "event field value"));
            });
            values = Map.copyOf(copy);
        }

        public static ObjectValue empty() { return new ObjectValue(Map.of()); }
    }
}
