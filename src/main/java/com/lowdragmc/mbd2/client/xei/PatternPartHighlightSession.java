package com.lowdragmc.mbd2.client.xei;

import com.lowdragmc.mbd2.api.pattern.preview.AbstractPartHighlightSession;
import com.lowdragmc.mbd2.api.pattern.preview.PartHighlightIndex;
import com.lowdragmc.mbd2.api.pattern.preview.PartHighlightSource;

/** Per-widget session for a definition-backed shape. */
public final class PatternPartHighlightSession extends AbstractPartHighlightSession {
    @Override
    protected PartHighlightIndex loadIndex(PartHighlightSource source) {
        return source.partHighlightIndex();
    }
}
