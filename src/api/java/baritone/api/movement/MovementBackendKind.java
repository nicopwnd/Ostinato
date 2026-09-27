package baritone.api.movement;

/**
 * Travel movement backend selection for goto-style goals.
 * Mining / digging / builder stay on classic Baritone pathing.
 */
public enum MovementBackendKind {
    BARITONE,
    TUNGSTEN,
    AUTO;

    public static MovementBackendKind fromString(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return AUTO;
        }
        switch (raw.trim().toLowerCase()) {
            case "baritone": case "bati": case "bt": case "classic": return BARITONE;
            case "tungsten": case "tung": case "physics": return TUNGSTEN;
            default: return AUTO;
        }
    }
}
