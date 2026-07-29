package dev.nulli0n.vbot;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeGenerationGateTest {
    @Test
    void delayedCallbackFromAnOlderRuntimeCannotMutateTheReplacement() {
        RuntimeGenerationGate gate = new RuntimeGenerationGate();
        long oldGeneration = gate.advance();
        AtomicInteger sideEffects = new AtomicInteger();
        Runnable delayedCallback = () -> {
            if (gate.matches(oldGeneration)) {
                sideEffects.incrementAndGet();
            }
        };

        long replacementGeneration = gate.advance();
        delayedCallback.run();

        assertThat(gate.matches(oldGeneration)).isFalse();
        assertThat(gate.matches(replacementGeneration)).isTrue();
        assertThat(sideEffects).hasValue(0);
    }
}
