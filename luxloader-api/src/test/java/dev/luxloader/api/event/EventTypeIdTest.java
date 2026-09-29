package dev.luxloader.api.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventTypeIdTest {
    @Test
    void parsesAndChecksNamespaceOwnershipAtPathBoundaries() {
        EventTypeId owner = EventTypeId.parse("example:weather");

        assertEquals(owner, new EventTypeId("example", "weather"));
        assertTrue(EventTypeId.parse("example:weather/rain").isOwnedBy(owner));
        org.junit.jupiter.api.Assertions.assertFalse(EventTypeId.parse("example:weatherx").isOwnedBy(owner));
        org.junit.jupiter.api.Assertions.assertFalse(EventTypeId.parse("other:weather/rain").isOwnedBy(owner));
        assertThrows(IllegalArgumentException.class, () -> EventTypeId.parse("example:weather:rain"));
        assertThrows(IllegalArgumentException.class, () -> new EventTypeId("example", "weather/../rain"));
    }

    @Test
    void nestedValuesAreCopiedAndImmutable() {
        ArrayList<EventValue> values = new ArrayList<>();
        values.add(new EventValue.Text("before"));
        LinkedHashMap<String, EventValue> fields = new LinkedHashMap<>();
        fields.put("values", new EventValue.Array(values));
        EventValue.ObjectValue payload = new EventValue.ObjectValue(fields);

        values.set(0, new EventValue.Text("after"));
        fields.clear();

        assertEquals("before", ((EventValue.Text) ((EventValue.Array) payload.values().get("values"))
                .values().getFirst()).value());
        assertThrows(UnsupportedOperationException.class,
                () -> payload.values().put("another", new EventValue.Flag(true)));
        assertThrows(UnsupportedOperationException.class,
                () -> ((EventValue.Array) payload.values().get("values")).values().add(new EventValue.Flag(false)));
    }
}
