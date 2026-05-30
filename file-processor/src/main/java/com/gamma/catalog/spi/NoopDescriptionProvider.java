package com.gamma.catalog.spi;

import com.gamma.catalog.Description;

/**
 * The lean core's default {@link DescriptionProvider}: it abstains from every column and table.
 *
 * <p>Its purpose is to prove the SPI is discovered and wired without the core depending on any
 * AI/LLM library — descriptions stay exactly as authored (or empty) until the
 * {@code file-processor-agent} module registers an AI-backed provider at M3.
 */
public final class NoopDescriptionProvider implements DescriptionProvider {

    @Override
    public String name() {
        return "noop";
    }

    @Override
    public Description describeColumn(ColumnContext ctx) {
        return Description.EMPTY;
    }
}
