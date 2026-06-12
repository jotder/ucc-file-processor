package com.gamma.agent.model;

import com.gamma.agentkernel.model.ModelProvider;
import com.gamma.agentkernel.model.ModelRouter;
import com.gamma.agentkernel.model.ModelTier;

/**
 * A {@link ModelRouter} with a hot-swappable delegate (v4.1) — the seam that makes
 * {@code POST /assist/settings} take effect live. Skills resolve providers per request through the
 * router handle they were initialised with; swapping the delegate re-routes every subsequent call
 * without touching the skills or the orchestrator.
 */
public final class DelegatingModelRouter implements ModelRouter {

    private volatile ModelRouter delegate;

    public DelegatingModelRouter(ModelRouter initial) {
        this.delegate = initial == null ? ModelRouter.of((ModelProvider) null) : initial;
    }

    /** Swap the routing live; in-flight calls finish on the old delegate. */
    public void set(ModelRouter next) {
        if (next != null) this.delegate = next;
    }

    @Override
    public ModelProvider providerFor(ModelTier tier) {
        return delegate.providerFor(tier);
    }
}
