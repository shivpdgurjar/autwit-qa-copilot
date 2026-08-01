package com.autwit.copilot.planning;

/**
 * The deliverables a {@link Generation} produces, each backed by its own orchestrator skill:
 * {@code planning.generate_test_plan} (Step 3), {@code planning.generate_test_data} (Step 4),
 * and {@code planning.analyze_documents} (the pre-generation reasoning pass). The wire form is
 * the lowercase token in {@code generation.generation_type}.
 */
public enum GenerationType {
    TEST_PLAN,
    TEST_DATA,
    DOCUMENT_ANALYSIS;

    public String wire() {
        return name().toLowerCase();
    }

    public static GenerationType fromWire(String wire) {
        return valueOf(wire.trim().toUpperCase());
    }
}
